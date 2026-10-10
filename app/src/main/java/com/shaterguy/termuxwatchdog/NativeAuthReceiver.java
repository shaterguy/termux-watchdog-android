package com.shaterguy.termuxwatchdog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** The callback carries only offers/readiness. Credentials leave only a verified FETCH reply. */
public final class NativeAuthReceiver extends BroadcastReceiver {
    private static final Semaphore SESSIONS=new Semaphore(4);
    private static final Semaphore UNATTRIBUTED_SESSIONS=new Semaphore(1);
    @Override public void onReceive(Context context,Intent intent) {
        if(intent==null || !BridgeHandshake.ACTION.equals(intent.getAction())) return;
        Context app=context.getApplicationContext();
        AuthPeerVerifier peers=new AuthPeerVerifier(app);
        int observedSender=-1;
        if(Build.VERSION.SDK_INT>=34) {
            try { observedSender=getSentFromUid(); } catch(RuntimeException ignored) { /* bounded compatibility lane */ }
        }
        final int sentFromUid=observedSender;
        if(sentFromUid>=0 && !peers.verify(sentFromUid)) return;
        final IBinder callback; final String challenge;
        try {
            Bundle extras=intent.getExtras();
            if(extras==null || extras.size()!=2 || !extras.containsKey("callback") || !extras.containsKey("challenge")) return;
            callback=extras.getBinder("callback"); challenge=extras.getString("challenge");
            if(callback==null || !BridgeHandshake.validChallenge(challenge)) return;
        } catch(RuntimeException ignored) { return; }
        final Semaphore slots=sentFromUid>=0 ? SESSIONS : UNATTRIBUTED_SESSIONS;
        if(!slots.tryAcquire()) return;
        PendingResult pending=goAsync();
        long expires=SystemClock.elapsedRealtime()+BridgeHandshake.SERVER_DEADLINE_MS;
        AuthBinderSession session=new AuthBinderSession(challenge,expires);
        AtomicBoolean finished=new AtomicBoolean();
        AtomicBoolean verifiedSession=new AtomicBoolean();
        AtomicBoolean successfulEnd=new AtomicBoolean();
        Handler handler=new Handler(Looper.getMainLooper());
        Runnable finish=new Runnable() {
            @Override public void run() {
                synchronized(NativeAuthBridge.deliveryLock()) {
                    if(finished.compareAndSet(false,true)) {
                        handler.removeCallbacks(this); session.close();
                        if(verifiedSession.get() && !successfulEnd.get()) NativeAuthBridge.noteFailure();
                        try { pending.finish(); } finally { slots.release(); }
                    }
                }
            }
        };
        handler.postDelayed(finish,BridgeHandshake.SERVER_DEADLINE_MS);
        Binder endpoint=new Binder() {
            private volatile boolean tokenReply;
            private volatile long admittedEpoch=-1;
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
                int peerUid=Binder.getCallingUid();
                int max=code==BridgeHandshake.TX_REQUEST ? BridgeHandshake.MAX_REQUEST_PARCEL_BYTES : BridgeHandshake.MAX_PARCEL_BYTES;
                if((sentFromUid>=0 && peerUid!=sentFromUid) || flags!=0 || reply==null || finished.get() || data.dataSize()>max || !peers.verify(peerUid)) return false;
                boolean keep=false;
                try {
                    data.enforceInterface(BridgeHandshake.ENDPOINT);
                    int version=data.readInt(); String supplied=data.readString(); long now=SystemClock.elapsedRealtime();
                    if(code==BridgeHandshake.TX_HELLO) {
                        if(data.dataAvail()!=0 || !session.hello(version,supplied,peerUid,now) || !peers.verify(peerUid)) return false;
                        verifiedSession.set(true); NativeAuthBridge.noteHandshake();
                        reply.writeNoException(); reply.writeInt(1); reply.writeString(challenge);
                        reply.writeInt(android.os.Process.myUid()); reply.writeInt(peerUid);
                        reply.writeInt(BuildConfig.NATIVE_TOKEN_TRANSPORT_ENABLED ? 1 : 0);
                        keep=true; return true;
                    }
                    // No credential bytes are decoded before both fresh identity and session checks.
                    if(!session.authorized(version,supplied,peerUid,now)) return false;
                    if(code==BridgeHandshake.TX_REQUEST) {
                        byte[] frame=data.createByteArray();
                        if(data.dataAvail()!=0 || frame==null || frame.length>AuthProtocol.MAX_FRAME_BYTES
                                || !session.accept(version,supplied,peerUid,now)) return false;
                        final AuthProtocol.Request request;
                        try { request=AuthProtocol.parse(AuthProtocol.read(new ByteArrayInputStream(frame))); }
                        catch(Exception ignored) { reply.writeNoException(); reply.writeString("BAD_REQUEST"); return true; }
                        if(!peers.verify(peerUid)) return false;
                        long identity=Binder.clearCallingIdentity();
                        try {
                            CompletableFuture<AuthCoordinator.Result> operation;
                            if(request.operation.equals("status")) {
                                String response=AuthProtocol.status(NativeAuthBridge.status(app));
                                if(session.complete(response,SystemClock.elapsedRealtime())) notifyReady(callback,challenge,finish);
                                reply.writeNoException(); reply.writeString(""); keep=true; return true;
                            }
                            if(expires-SystemClock.elapsedRealtime()<BridgeHandshake.PROVIDER_BUDGET_MS) {
                                operation=CompletableFuture.completedFuture(AuthCoordinator.Result.error("AUTH_TIMEOUT"));
                            } else {
                                long queueBudget=Math.max(0,expires-SystemClock.elapsedRealtime()-BridgeHandshake.PROVIDER_BUDGET_MS);
                                NativeAuthBridge.Admission admission=NativeAuthBridge.request(app,request.rejectedToken,System.nanoTime()+queueBudget*1_000_000L);
                                admittedEpoch=admission.epoch; operation=admission.operation;
                            }
                            operation.whenComplete((result,failure)->{
                                if(finished.get()) return;
                                AuthCoordinator.Result safe=failure==null && result!=null ? result : AuthCoordinator.Result.error("AUTH_UNAVAILABLE");
                                tokenReply=safe.ok();
                                String response=safe.ok() ? AuthProtocol.success(safe.token) : AuthProtocol.error(safe.code);
                                if(session.complete(response,SystemClock.elapsedRealtime())) notifyReady(callback,challenge,finish);
                            });
                            reply.writeNoException(); reply.writeString(""); keep=true; return true;
                        } finally { Binder.restoreCallingIdentity(identity); }
                    }
                    if(data.dataAvail()!=0) return false;
                    if(code==BridgeHandshake.TX_FETCH) {
                        if(!peers.verify(peerUid)) return false;
                        synchronized(NativeAuthBridge.deliveryLock()) {
                            long deliveryNow=SystemClock.elapsedRealtime();
                            if(finished.get() || deliveryNow>=expires) return false;
                            String response=session.take(version,supplied,peerUid,deliveryNow);
                            if(response==null) return false;
                            if(tokenReply && !NativeAuthBridge.deliveryAllowed(app,admittedEpoch)) response=AuthProtocol.error("AUTH_UNAVAILABLE");
                            if(SystemClock.elapsedRealtime()>=expires || finished.get()) return false;
                            successfulEnd.set(true); NativeAuthBridge.noteHandshake();
                            reply.writeNoException(); reply.writeByteArray(response.getBytes(StandardCharsets.US_ASCII));
                            return true;
                        }
                    }
                    if(code==BridgeHandshake.TX_CLOSE) { successfulEnd.set(true); reply.writeNoException(); return true; }
                    return false;
                } catch(RuntimeException ignored) { return false; }
                finally { if(!keep) finish.run(); }
            }
        };
        Parcel offer=Parcel.obtain();
        try {
            offer.writeInterfaceToken(BridgeHandshake.CALLBACK); offer.writeInt(1);
            offer.writeString(challenge); offer.writeStrongBinder(endpoint);
            if(!callback.transact(BridgeHandshake.CALLBACK_OFFER,offer,null,IBinder.FLAG_ONEWAY)) finish.run();
        } catch(RemoteException | RuntimeException ignored) { finish.run(); }
        finally { offer.recycle(); }
    }
    private static void notifyReady(IBinder callback,String challenge,Runnable finish) {
        Parcel ready=Parcel.obtain();
        try {
            ready.writeInterfaceToken(BridgeHandshake.CALLBACK); ready.writeInt(1); ready.writeString(challenge);
            if(!callback.transact(BridgeHandshake.CALLBACK_READY,ready,null,IBinder.FLAG_ONEWAY)) finish.run();
        } catch(RemoteException | RuntimeException ignored) { finish.run(); }
        finally { ready.recycle(); }
    }
}

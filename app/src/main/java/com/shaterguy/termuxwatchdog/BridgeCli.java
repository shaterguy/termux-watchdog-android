package com.shaterguy.termuxwatchdog;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Execute only a reviewed hash-pinned standalone DEX. Credentials use stdin/stdout pipes only. */
public final class BridgeCli {
    private static final AtomicBoolean completed=new AtomicBoolean();
    private static boolean persistent;
    private static void finish(String frame,int exit) {
        if(completed.compareAndSet(false,true)) {
            System.out.print(frame); System.out.flush();
            // Early failures may leave unread request bytes. Never reuse that stream.
            if(!persistent || exit!=0) Runtime.getRuntime().halt(exit);
        }
    }
    private static void fail(String code) { finish(AuthProtocol.error(code),2); }
    public static void main(String[] args) {
        persistent=args.length==1 && "--persistent".equals(args[0]);
        if(!persistent) { runExchange(args); return; }
        while(true) {
            final String marker;
            try { marker=readLine(System.in,3,true); }
            catch(IOException ignored) { Runtime.getRuntime().halt(2); return; }
            if(marker==null) { Runtime.getRuntime().halt(0); return; }
            if(!"B1".equals(marker)) { Runtime.getRuntime().halt(2); return; }
            completed.set(false);
            runExchange(new String[0]);
        }
    }
    private static void runExchange(String[] args) {
        Thread deadline=new Thread(()->{
            try { Thread.sleep(8000); } catch(InterruptedException ignored) { return; }
            // No pipe write here: a blocked stdout cannot defeat the hard deadline.
            Runtime.getRuntime().halt(124);
        },"watchdog-bridge-deadline");
        deadline.setDaemon(true); deadline.start();
        try { exchange(args); } finally { deadline.interrupt(); }
    }
    /** Bounded stream framing; the payload reader is called only after mutual hello. */
    private static String readLine(InputStream input,int limit,boolean allowCleanEof) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(int count=0;count<limit;count++) {
            int next=input.read();
            if(next<0 && count==0 && allowCleanEof) return null;
            if(next=='\n') return bytes.toString(StandardCharsets.US_ASCII.name());
            if(next<0 || next>127 || next=='\r' || (next<32 && next!='\t')) throw new IOException("BAD_REQUEST");
            bytes.write(next);
        }
        throw new IOException("BAD_REQUEST");
    }
    private static void exchange(String[] args) {
        if(args.length!=0) { fail("BAD_REQUEST"); return; }
        final AuthPeerVerifier peers;
        try { peers=new AuthPeerVerifier(new StandalonePackages()); }
        catch(Exception ignored) { fail("AUTH_UNAVAILABLE"); return; }
        if(!peers.configured()) { fail("AUTH_NOT_CONFIGURED"); return; }
        if(!peers.verify(Process.myUid()) || peers.verifiedWatchdogUid()<0) { fail("PEER_DENIED"); return; }
        SecureRandom random=new SecureRandom(); byte[] nonce=new byte[32]; random.nextBytes(nonce);
        StringBuilder hex=new StringBuilder(64);
        for(byte value:nonce) hex.append(String.format(java.util.Locale.ROOT,"%02x",value & 0xff));
        String challenge=hex.toString();
        CompletableFuture<Offer> offered=new CompletableFuture<>();
        CompletableFuture<Void> ready=new CompletableFuture<>();
        Binder callback=new Binder() {
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
                int peerUid=Binder.getCallingUid();
                if((flags & IBinder.FLAG_ONEWAY)==0 || data.dataSize()>BridgeHandshake.MAX_PARCEL_BYTES || !peers.verifyWatchdog(peerUid)) return false;
                try {
                    data.enforceInterface(BridgeHandshake.CALLBACK);
                    int version=data.readInt(); String returned=data.readString();
                    if(version!=1 || !challenge.equals(returned)) return false;
                    if(code==BridgeHandshake.CALLBACK_OFFER) {
                        IBinder endpoint=data.readStrongBinder();
                        return endpoint!=null && data.dataAvail()==0 && offered.complete(new Offer(endpoint,peerUid));
                    }
                    if(code==BridgeHandshake.CALLBACK_READY) {
                        Offer offer=offered.getNow(null);
                        return data.dataAvail()==0 && offer!=null && offer.uid==peerUid && ready.complete(null);
                    }
                    return false;
                } catch(RuntimeException ignored) { return false; }
            }
        };
        try {
            Bundle extras=new Bundle(); extras.putBinder("callback",callback); extras.putString("challenge",challenge);
            Intent intent=new Intent(BridgeHandshake.ACTION).setComponent(new ComponentName(BridgeHandshake.PACKAGE,BridgeHandshake.RECEIVER));
            intent.putExtras(extras); BinderBootstrap.send(intent,random.nextInt());
            Offer offer=offered.get(6,TimeUnit.SECONDS);
            boolean activated=hello(offer,peers,challenge);
            // Identity is established before request bytes, including a rejection, are consumed.
            final String input;
            try {
                if(persistent) {
                    String line=readLine(System.in,AuthProtocol.MAX_FRAME_BYTES,false);
                    input=AuthProtocol.read(new ByteArrayInputStream((line+"\n").getBytes(StandardCharsets.US_ASCII)));
                } else input=AuthProtocol.read(System.in);
            }
            catch(Exception ignored) { close(offer,peers,challenge); fail("BAD_REQUEST"); return; }
            if(AuthProtocol.isProbe(input)) {
                close(offer,peers,challenge);
                finish("{\"version\":1,\"ok\":true,\"transport\":\"binder\",\"peerUid\":"+offer.uid
                        +",\"requesterUid\":"+Process.myUid()+",\"challenge\":\""+challenge+"\",\"tokenTransportEnabled\":"+activated+"}\n",0);
                return;
            }
            final AuthProtocol.Request operation;
            try { operation=AuthProtocol.parse(input); }
            catch(IllegalArgumentException ignored) { close(offer,peers,challenge); fail("BAD_REQUEST"); return; }
            if(!activated && !operation.operation.equals("status")) { close(offer,peers,challenge); fail("AUTH_NOT_CONFIGURED"); return; }
            submit(offer,peers,challenge,(input+"\n").getBytes(StandardCharsets.US_ASCII));
            ready.get(6,TimeUnit.SECONDS);
            String response=fetch(offer,peers,challenge);
            if(!AuthProtocol.validResponse(response,operation.operation.equals("status"))) { fail("AUTH_UNAVAILABLE"); return; }
            finish(response+"\n",response.contains("\"ok\":false") ? 2 : 0);
        } catch(BridgeFailure failure) { fail(failure.code); }
        catch(TimeoutException ignored) { fail("AUTH_TIMEOUT"); }
        catch(Exception ignored) { fail("AUTH_UNAVAILABLE"); }
    }
    private static void verify(Offer offer,AuthPeerVerifier peers) throws BridgeFailure {
        if(!peers.verify(Process.myUid()) || !peers.verifyWatchdog(offer.uid)) throw new BridgeFailure("PEER_DENIED");
    }
    private static Parcel request(String challenge) {
        Parcel data=Parcel.obtain(); data.writeInterfaceToken(BridgeHandshake.ENDPOINT);
        data.writeInt(1); data.writeString(challenge); return data;
    }
    private static boolean hello(Offer offer,AuthPeerVerifier peers,String challenge) throws Exception {
        verify(offer,peers); Parcel data=request(challenge); Parcel reply=Parcel.obtain();
        try {
            if(!offer.endpoint.transact(BridgeHandshake.TX_HELLO,data,reply,0) || reply.dataSize()>BridgeHandshake.MAX_PARCEL_BYTES) throw new BridgeFailure("AUTH_UNAVAILABLE");
            reply.readException(); int version=reply.readInt(); String returned=reply.readString();
            int uid=reply.readInt(); int requester=reply.readInt();
            if(reply.dataAvail()!=4) throw new BridgeFailure("AUTH_UNAVAILABLE");
            int activated=reply.readInt();
            if(version!=1 || !challenge.equals(returned) || uid!=offer.uid || requester!=Process.myUid()
                    || (activated!=0 && activated!=1) || reply.dataAvail()!=0) throw new BridgeFailure("PEER_DENIED");
            verify(offer,peers); return activated==1;
        } finally { data.recycle(); reply.recycle(); }
    }
    private static void submit(Offer offer,AuthPeerVerifier peers,String challenge,byte[] frame) throws Exception {
        verify(offer,peers); Parcel data=request(challenge); Parcel reply=Parcel.obtain();
        try {
            data.writeByteArray(frame);
            if(!offer.endpoint.transact(BridgeHandshake.TX_REQUEST,data,reply,0) || reply.dataSize()>BridgeHandshake.MAX_PARCEL_BYTES) throw new BridgeFailure("AUTH_UNAVAILABLE");
            reply.readException(); String error=reply.readString();
            if(error==null || reply.dataAvail()!=0) throw new BridgeFailure("AUTH_UNAVAILABLE");
            if(!error.isEmpty()) throw new BridgeFailure(AuthProtocol.safeError(error));
            verify(offer,peers);
        } finally { data.recycle(); reply.recycle(); }
    }
    private static String fetch(Offer offer,AuthPeerVerifier peers,String challenge) throws Exception {
        verify(offer,peers); Parcel data=request(challenge); Parcel reply=Parcel.obtain();
        try {
            if(!offer.endpoint.transact(BridgeHandshake.TX_FETCH,data,reply,0) || reply.dataSize()>BridgeHandshake.MAX_REQUEST_PARCEL_BYTES) throw new BridgeFailure("AUTH_UNAVAILABLE");
            // Re-check the current owner before decoding a credential-bearing response.
            verify(offer,peers); reply.readException(); byte[] frame=reply.createByteArray();
            if(frame==null || frame.length>AuthProtocol.MAX_FRAME_BYTES || reply.dataAvail()!=0) throw new BridgeFailure("AUTH_UNAVAILABLE");
            return AuthProtocol.read(new ByteArrayInputStream(frame));
        } finally { data.recycle(); reply.recycle(); }
    }
    private static void close(Offer offer,AuthPeerVerifier peers,String challenge) throws Exception {
        verify(offer,peers); Parcel data=request(challenge); Parcel reply=Parcel.obtain();
        try {
            if(!offer.endpoint.transact(BridgeHandshake.TX_CLOSE,data,reply,0) || reply.dataSize()>BridgeHandshake.MAX_PARCEL_BYTES) throw new BridgeFailure("AUTH_UNAVAILABLE");
            reply.readException(); if(reply.dataAvail()!=0) throw new BridgeFailure("AUTH_UNAVAILABLE");
        } finally { data.recycle(); reply.recycle(); }
    }
    private static final class Offer {
        final IBinder endpoint; final int uid;
        Offer(IBinder endpoint,int uid) { this.endpoint=endpoint; this.uid=uid; }
    }
    private static final class BridgeFailure extends Exception {
        final String code;
        BridgeFailure(String code) { super("BRIDGE_FAILURE"); this.code=AuthProtocol.safeError(code); }
    }
}

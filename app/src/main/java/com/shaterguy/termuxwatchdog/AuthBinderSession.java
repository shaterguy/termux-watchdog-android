package com.shaterguy.termuxwatchdog;

/** Single verified requester, one async operation and one destructive fetch; secrets stay in memory. */
final class AuthBinderSession {
    private enum Phase { NEW, VERIFIED, PENDING, READY, CLOSED }
    private final String challenge;
    private final long deadline;
    private Phase phase=Phase.NEW;
    private int requesterUid=-1;
    private String response;
    AuthBinderSession(String challenge,long deadline) {
        if (!BridgeHandshake.validChallenge(challenge)) throw new IllegalArgumentException("BAD_REQUEST");
        this.challenge=challenge; this.deadline=deadline;
    }
    private boolean matches(int version,String nonce,int uid,long now) {
        return version==1 && challenge.equals(nonce) && uid==requesterUid && now<deadline;
    }
    synchronized boolean hello(int version,String nonce,int uid,long now) {
        if (phase!=Phase.NEW || version!=1 || !challenge.equals(nonce) || uid<10000 || now>=deadline) { close(); return false; }
        requesterUid=uid; phase=Phase.VERIFIED; return true;
    }
    synchronized boolean authorized(int version,String nonce,int uid,long now) {
        return phase!=Phase.NEW && phase!=Phase.CLOSED && matches(version,nonce,uid,now);
    }
    synchronized boolean accept(int version,String nonce,int uid,long now) {
        if (phase!=Phase.VERIFIED || !matches(version,nonce,uid,now)) { close(); return false; }
        phase=Phase.PENDING; return true;
    }
    synchronized boolean complete(String frame,long now) {
        if (phase!=Phase.PENDING || now>=deadline) { if(now>=deadline) close(); return false; }
        if (frame==null || frame.length()>AuthProtocol.MAX_FRAME_BYTES || !frame.endsWith("\n")) { close(); return false; }
        for(int i=0;i<frame.length()-1;i++) if(frame.charAt(i)<32 || frame.charAt(i)>126) { close(); return false; }
        response=frame; phase=Phase.READY; return true;
    }
    synchronized String take(int version,String nonce,int uid,long now) {
        if (phase!=Phase.READY || !matches(version,nonce,uid,now)) { close(); return null; }
        String value=response; close(); return value;
    }
    synchronized void close() { response=null; phase=Phase.CLOSED; }
}

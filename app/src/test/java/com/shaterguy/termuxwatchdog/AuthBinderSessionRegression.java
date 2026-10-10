package com.shaterguy.termuxwatchdog;
public final class AuthBinderSessionRegression {
    private static int count;
    private static final String NONCE="a".repeat(64);
    private static void check(boolean value) { count++; if (!value) throw new AssertionError("session check "+count); }
    private static AuthBinderSession verified() {
        AuthBinderSession s=new AuthBinderSession(NONCE,7000);
        check(s.hello(1,NONCE,10031,1)); return s;
    }
    public static void main(String[] args) {
        AuthBinderSession s=new AuthBinderSession(NONCE,7000);
        check(!s.accept(1,NONCE,10031,1));
        s=new AuthBinderSession(NONCE,7000); check(!s.hello(2,NONCE,10031,1));
        s=new AuthBinderSession(NONCE,7000); check(!s.hello(1,NONCE,999,1));
        s=new AuthBinderSession(NONCE,7000); check(!s.hello(1,NONCE,10031,7000));
        s=verified(); check(!s.hello(1,NONCE,10031,2));
        s=verified(); check(!s.accept(1,NONCE,10032,2));
        s=verified(); check(s.accept(1,NONCE,10031,2)); check(!s.accept(1,NONCE,10031,3));
        s=verified(); check(s.take(1,NONCE,10031,3)==null);
        s=verified(); check(s.accept(1,NONCE,10031,2));
        String response=AuthProtocol.success("synthetic-token.1");
        check(s.complete(response,3)); check(!s.complete(response,4));
        check(s.take(1,NONCE,10032,4)==null);
        s=verified(); check(s.accept(1,NONCE,10031,2)); check(s.complete(response,3));
        check(response.equals(s.take(1,NONCE,10031,4))); check(s.take(1,NONCE,10031,5)==null);
        s=verified(); check(s.accept(1,NONCE,10031,2)); check(!s.complete(response,7000)); check(s.take(1,NONCE,10031,4)==null);
        s=verified(); check(s.accept(1,NONCE,10031,2)); s.close(); check(!s.complete(response,3));
        check(!AuthAvailability.mayUse(false,true,true,false));
        check(!AuthAvailability.mayUse(true,false,true,false));
        check(!AuthAvailability.mayUse(true,true,false,false));
        check(!AuthAvailability.mayUse(true,true,true,true));
        check(AuthAvailability.mayUse(true,true,true,false));
        check(AuthAvailability.state(false,true,true,false,"ready").equals("not_configured"));
        check(AuthAvailability.state(true,false,true,false,"ready").equals("not_ready"));
        check(AuthAvailability.state(true,true,true,true,"ready").equals("not_ready"));
        check(AuthAvailability.state(true,true,true,false,"reauth_required").equals("reauth_required"));
        AuthDeliveryGate gate=new AuthDeliveryGate();
        long accepted=gate.epoch(); check(gate.current(accepted));
        gate.revoke(); check(!gate.current(accepted));
        long reenabled=gate.epoch(); check(gate.current(reenabled));
        gate.revoke(); check(!gate.current(accepted) && !gate.current(reenabled));
        System.out.println("AUTH_BINDER_SESSION_PASS checks="+count);
    }
}

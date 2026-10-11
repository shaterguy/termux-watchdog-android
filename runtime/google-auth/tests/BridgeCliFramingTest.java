package com.shaterguy.termuxwatchdog;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
public final class BridgeCliFramingTest {
    private static final Method READ;
    static {try{READ=BridgeCli.class.getDeclaredMethod("readLine",InputStream.class,int.class,boolean.class);READ.setAccessible(true);}catch(Exception e){throw new RuntimeException(e);}}
    private static String read(InputStream input,int limit,boolean eof)throws Exception {
        try{return (String)READ.invoke(null,input,limit,eof);}catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
    private static ByteArrayInputStream bytes(String text){return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));}
    private static void equal(Object actual,Object expected){if(!java.util.Objects.equals(actual,expected))throw new AssertionError();}
    private static void reject(String text,int max)throws Exception{try{read(bytes(text),max,false);throw new AssertionError();}catch(IOException expected){}}
    public static void main(String[] args)throws Exception {
        ByteArrayInputStream stream=bytes("B1\n{\"version\":1,\"op\":\"status\"}\nB1\n");
        equal(read(stream,3,true),"B1");equal(AuthProtocol.parse(read(stream,16384,false)).operation,"status");equal(read(stream,3,true),"B1");
        equal(read(stream,3,true),null);reject("B12\n",3);reject("B1",3);reject("x\r\n",4);reject("x\u0000\n",4);
        equal(read(bytes("x".repeat(16383)+"\n"),16384,false).length(),16383);
        reject("x".repeat(16384)+"\n",16384);
        try{AuthProtocol.read(bytes("{}\n{}\n"));throw new AssertionError();}catch(IOException expected){}
        System.out.println("BRIDGE_FRAMING_PASS cases=11 legacy_eof_preserved=true");
    }
}

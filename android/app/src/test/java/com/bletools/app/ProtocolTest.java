package com.bletools.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** No JUnit or Android runtime needed. Fixtures were generated from the unchanged desktop codec. */
public final class ProtocolTest {
    private static int checks;
    private static void check(boolean result,String message) {checks++;if(!result)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message) {
        boolean failed=false;try{action.run();}catch(IllegalArgumentException e){failed=true;}check(failed,message);
    }
    private static Protocol.Message message(int type,byte[] body) {return new Protocol.Decoder().feed(new Protocol().frame(type,body,0,1)).get(0);}
    public static void main(String[] args) throws Exception {
        Map<String,byte[]> bodies=new LinkedHashMap<>();Map<String,Integer> ids=new LinkedHashMap<>();
        bodies.put("ping",Protocol.string(1,"Hello from Android"));ids.put("ping",60206);
        bodies.put("ping_utf8",Protocol.string(1,"你好 BLE"));ids.put("ping_utf8",60206);
        byte[] chunk=new byte[256];for(int i=0;i<chunk.length;i++)chunk[i]=(byte)i;
        bodies.put("file_first",Protocol.fileWrite("vol0:test.bin",0,220400,chunk,true));ids.put("file_first",60805);
        bodies.put("file_later",Protocol.fileWrite("vol0:测试.bin",1800,5242880,new byte[]{0,(byte)255,0x5a},false));ids.put("file_later",60805);
        int fixtures=0;
        for(String line:Files.readAllLines(Paths.get(args[0]),StandardCharsets.UTF_8)) {
            if(line.isEmpty())continue;String[] parts=line.split("\t");fixtures++;
            byte[] encoded=new Protocol().frame(ids.get(parts[0]),bodies.get(parts[0]),0,1);
            check(Arrays.equals(encoded,Protocol.unhex(parts[1])),"Desktop compatibility: "+parts[0]);
            // Every possible split is a meaningful notification boundary, including inside both CRCs.
            for(int split=0;split<=encoded.length;split++) {
                Protocol.Decoder decoder=new Protocol.Decoder();List<Protocol.Message> all=new ArrayList<>();
                all.addAll(decoder.feed(Arrays.copyOfRange(encoded,0,split)));
                all.addAll(decoder.feed(Arrays.copyOfRange(encoded,split,encoded.length)));
                check(all.size()==1 && all.get(0).type==ids.get(parts[0]) && Arrays.equals(all.get(0).body,bodies.get(parts[0])),"Split frame: "+parts[0]+"/"+split);
            }
        }
        check(fixtures==4,"All Ping and file transfer desktop vectors present");
        byte[] frame=new Protocol().frame(Protocol.SUCCESS,Protocol.string(1,"OK"),0,1);
        Protocol.Decoder decoder=new Protocol.Decoder();
        check(decoder.feed(Protocol.concat(new byte[]{0,1,2},frame,frame)).size()==2,"Noise and concatenated frames");
        byte[] corrupt=frame.clone();corrupt[corrupt.length-1]^=1;
        check(new Protocol.Decoder().feed(Protocol.concat(corrupt,frame)).size()==1,"Reject tail CRC, recover next frame");
        corrupt=frame.clone();corrupt[3]^=1;
        check(new Protocol.Decoder().feed(Protocol.concat(corrupt,frame)).size()==1,"Reject header CRC");
        corrupt=frame.clone();corrupt[1]=2;corrupt[2]=0;corrupt[3]=(byte)Protocol.crc(corrupt,3);
        check(new Protocol.Decoder().feed(Protocol.concat(corrupt,frame)).size()==1,"Reject undersized frame");
        Protocol serial=new Protocol();for(int i=1;i<=512;i++)check((serial.frame(Protocol.PING,new byte[0],0,1)[6]&255)==(i-1)%255+1,"Sequence wrap");
        byte[] large=new byte[2048];new Random(7).nextBytes(large);
        byte[] largeFrame=new Protocol().frame(Protocol.FILE_WRITE,Protocol.fileWrite("vol0:x",0,2048,large,true),0,1);
        for(int size:new int[]{20,182,244,512}) {
            decoder=new Protocol.Decoder();List<Protocol.Message> messages=new ArrayList<>();
            for(int i=0;i<largeFrame.length;i+=size)messages.addAll(decoder.feed(Arrays.copyOfRange(largeFrame,i,Math.min(i+size,largeFrame.length))));
            check(messages.size()==1 && messages.get(0).type==Protocol.FILE_WRITE,"MTU fragmentation "+size);
        }
        rejects(()->Protocol.fields(new byte[]{8,(byte)128}),"Truncated varint");
        rejects(()->Protocol.fields(new byte[]{10,10,1}),"Truncated bytes");
        rejects(()->Protocol.fields(new byte[]{0}),"Invalid tag");
        rejects(()->Protocol.fields(new byte[]{11}),"Unsupported group wire");
        rejects(()->Protocol.fields(new byte[]{8,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,2}),"Overflow varint");
        rejects(()->Protocol.unhex("0 12"),"Odd hex");rejects(()->Protocol.unhex("ZZ"),"Invalid hex");
        rejects(()->new Protocol().frame(0,new byte[65526],0,1),"Frame size bound");
        check(Arrays.equals(Protocol.unhex("01 02\nFF"),new byte[]{1,2,(byte)255}),"Whitespace in hex");
        byte[] unknown=Protocol.concat(Protocol.uint(99,123),Protocol.string(1,"hello"));
        check(Protocol.text(unknown,1).equals("hello"),"Unknown protobuf fields skipped");
        check(Protocol.describe(message(Protocol.FAILURE,Protocol.concat(Protocol.uint(1,7),Protocol.string(2,"denied"),Protocol.uint(3,9)))).contains("subcode=9"),"Failure detail");
        checks += FileUploaderTest.run();
        System.out.println("PASS: "+checks+" checks; "+fixtures+" desktop wire vectors, fragmentation, CRC, parsing and windowed uploads (N=1..5).");
    }
}

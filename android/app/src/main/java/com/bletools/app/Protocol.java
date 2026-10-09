package com.bletools.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Wire-compatible with ble_tool.py: Proto V0 + two-byte message ID + protobuf. */
public final class Protocol {
    public static final int PING=60206, SUCCESS=60207, FAILURE=60208, FILE=60803, FILE_WRITE=60805;
    private int sequence;

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }
    public static byte[] varint(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative unsigned integer");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do { int b=(int)(value & 127); value >>>= 7; out.write(value == 0 ? b : b|128); } while(value != 0);
        return out.toByteArray();
    }
    public static byte[] uint(int field, long value) { return concat(varint((long)field<<3), varint(value)); }
    public static byte[] bytes(int field, byte[] value) { return concat(varint(((long)field<<3)|2), varint(value.length), value); }
    public static byte[] string(int field, String value) { return value.isEmpty() ? new byte[0] : bytes(field, value.getBytes(StandardCharsets.UTF_8)); }
    public static int crc(byte[] data, int count) {
        int crc=0x30;
        for(int i=0;i<count;i++) {
            crc ^= data[i]&255;
            for(int bit=0;bit<8;bit++) crc = (crc & 1) != 0 ? (crc>>>1)^0x8c : crc>>>1;
        }
        return crc;
    }
    public byte[] frame(int type, byte[] protobuf, int source, int router) {
        if(protobuf.length > 65525) throw new IllegalArgumentException("Frame too large");
        byte[] out=new byte[protobuf.length+10];
        out[0]=0x5a; out[1]=(byte)out.length; out[2]=(byte)(out.length>>>8);
        out[3]=(byte)crc(out,3); out[4]=(byte)router; out[5]=(byte)((source&15)<<2);
        sequence=sequence%255+1; out[6]=(byte)sequence;
        out[7]=(byte)type; out[8]=(byte)(type>>>8);
        System.arraycopy(protobuf,0,out,9,protobuf.length);
        out[out.length-1]=(byte)crc(out,out.length-1);
        return out;
    }
    public static final class Message {
        public final int type, sequence;
        public final byte[] body;
        Message(byte[] frame) {
            type=(frame[7]&255)|((frame[8]&255)<<8); sequence=frame[6]&255;
            body=Arrays.copyOfRange(frame,9,frame.length-1);
        }
    }
    /** Handles split notifications, concatenated frames and corrupt input without unbounded buffering. */
    public static final class Decoder {
        private byte[] pending=new byte[0];
        public List<Message> feed(byte[] chunk) {
            if(chunk.length > 131070) throw new IllegalArgumentException("Notification too large");
            byte[] data=concat(pending,chunk);
            List<Message> messages=new ArrayList<>();
            int pos=0;
            while(pos<data.length) {
                if(data[pos] != 0x5a) { pos++; continue; }
                if(data.length-pos<4) break;
                int len=(data[pos+1]&255)|((data[pos+2]&255)<<8);
                if(len<10 || (byte)crc(Arrays.copyOfRange(data,pos,pos+3),3)!=data[pos+3]) { pos++; continue; }
                if(data.length-pos<len) break;
                byte[] frame=Arrays.copyOfRange(data,pos,pos+len);
                if((byte)crc(frame,len-1)!=frame[len-1]) { pos++; continue; }
                messages.add(new Message(frame)); pos+=len;
            }
            pending=Arrays.copyOfRange(data,pos,data.length);
            return messages;
        }
    }
    public static final class Field {
        public final int number, wire;
        public final long value;
        public final byte[] data;
        Field(int n,int w,long v,byte[] d) {number=n;wire=w;value=v;data=d;}
        public String text() {return new String(data,StandardCharsets.UTF_8);}
    }
    private static long readVarint(byte[] data,int[] pos) {
        long value=0;
        for(int shift=0;shift<64;shift+=7) {
            if(pos[0]>=data.length) throw new IllegalArgumentException("Truncated protobuf varint");
            int b=data[pos[0]++]&255;
            if(shift==63 && (b&254)!=0) throw new IllegalArgumentException("Protobuf varint overflow");
            value |= (long)(b&127)<<shift;
            if((b&128)==0) return value;
        }
        throw new IllegalArgumentException("Protobuf varint overflow");
    }
    public static List<Field> fields(byte[] data) {
        List<Field> out=new ArrayList<>(); int[] pos={0};
        while(pos[0]<data.length) {
            long tag=readVarint(data,pos); int number=(int)(tag>>>3), wire=(int)(tag&7);
            if(number<1 || tag>>>3>536870911) throw new IllegalArgumentException("Invalid protobuf tag");
            if(wire==0) {out.add(new Field(number,wire,readVarint(data,pos),new byte[0])); continue;}
            long len=wire==2 ? readVarint(data,pos) : wire==1 ? 8 : wire==5 ? 4 : -1;
            if(len<0 || len>data.length-pos[0]) throw new IllegalArgumentException("Invalid protobuf field length/type");
            byte[] value=Arrays.copyOfRange(data,pos[0],pos[0]+(int)len); pos[0]+=(int)len;
            out.add(new Field(number,wire,0,value));
        }
        return out;
    }
    public static long number(byte[] data,int field,long fallback) {
        for(Field f:fields(data)) if(f.number==field && f.wire==0) return f.value;
        return fallback;
    }
    public static String text(byte[] data,int field) {
        for(Field f:fields(data)) if(f.number==field && f.wire==2) return f.text();
        return "";
    }
    public static byte[] fileWrite(String path,long offset,long total,byte[] chunk,boolean overwrite) {
        byte[] file=concat(string(1,path),uint(2,offset),uint(3,total),chunk.length==0 ? new byte[0] : bytes(4,chunk));
        return concat(bytes(1,file),uint(2,overwrite?1:0),uint(3,0));
    }
    public static String hex(byte[] data) {
        StringBuilder s=new StringBuilder(data.length*3);
        for(byte b:data) {if(s.length()>0)s.append(' '); s.append(String.format(Locale.ROOT,"%02X",b&255));}
        return s.toString();
    }
    public static byte[] unhex(String value) {
        String compact=value.replaceAll("\\s", "");
        if((compact.length()&1)!=0 || !compact.matches("[0-9a-fA-F]*")) throw new IllegalArgumentException("请输入成对的十六进制字节，例如 01 02 FF");
        byte[] out=new byte[compact.length()/2];
        for(int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(compact.substring(i*2,i*2+2),16);
        return out;
    }
    public static String describe(Message message) {
        if(message.type==SUCCESS) return "Success: "+text(message.body,1);
        if(message.type==FAILURE) return "Failure "+number(message.body,1,0)+": "+text(message.body,2)+" (subcode="+number(message.body,3,0)+")";
        return "Message "+message.type+"\n"+hex(message.body);
    }
    public Protocol() {}
}

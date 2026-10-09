package com.bletools.app;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** All operations run on one worker; callbacks complete only the matching in-flight operation. */
@SuppressLint("MissingPermission")
public final class BleClient {
    public interface Listener {
        void log(String line);
        void disconnected(String reason);
    }
    private interface Start { boolean run(BluetoothGatt gatt); }
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Listener listener;
    private volatile BluetoothGatt gatt;
    private volatile int mtu=23;
    private volatile boolean ready;
    private final Map<String,Integer> subscriptions=new ConcurrentHashMap<>();
    private Pending pending;
    private volatile Inbox inbox;
    private static final class Pending {
        final String kind,key;
        final CompletableFuture<byte[]> future=new CompletableFuture<>();
        Pending(String kind,String key) {this.kind=kind;this.key=key;}
    }
    private static final class Inbox {
        final String key;
        final Protocol.Decoder decoder=new Protocol.Decoder();
        final BlockingQueue<Object> messages=new ArrayBlockingQueue<>(64);
        Inbox(String key) {this.key=key;}
    }
    public BleClient(Context context,Listener listener) {this.context=context;this.listener=listener;}
    public boolean isReady() {return ready;}
    public int mtu() {return mtu;}
    public int payloadLimit() {return Math.min(512,mtu-3);}
    public static String key(BluetoothGattCharacteristic c) {
        return c.getService().getUuid()+":"+c.getService().getInstanceId()+"/"+c.getUuid()+":"+c.getInstanceId();
    }
    private static String key(BluetoothGattDescriptor d) {return key(d.getCharacteristic())+"/"+d.getUuid();}
    public List<BluetoothGattService> services() {BluetoothGatt g=gatt; return g==null?Collections.emptyList():g.getServices();}
    public int subscription(BluetoothGattCharacteristic c) {Integer v=subscriptions.get(key(c));return v==null?0:v;}
    private synchronized void complete(BluetoothGatt g,String kind,String key,int status,byte[] value) {
        if(g!=gatt || pending==null || !pending.kind.equals(kind) || !pending.key.equals(key)) return;
        if(status==BluetoothGatt.GATT_SUCCESS) pending.future.complete(value==null?new byte[0]:value.clone());
        else pending.future.completeExceptionally(new IOException(kind+" GATT status="+status));
    }
    private byte[] await(Pending p,int seconds) throws Exception {
        try {return p.future.get(seconds,TimeUnit.SECONDS);}
        catch(TimeoutException e) {disconnect("GATT 超时，已释放连接，请重新连接"); throw new IOException(p.kind+" 超时",e);}
        catch(ExecutionException e) {throw new IOException(e.getCause().getMessage(),e.getCause());}
        finally {synchronized(this) {if(pending==p) pending=null;}}
    }
    private byte[] operation(String kind,String key,Start action) throws Exception {
        Pending p=new Pending(kind,key);
        synchronized(this) {
            if(gatt==null) throw new IOException("未连接设备");
            if(pending!=null) throw new IOException("另一个 GATT 操作正在进行");
            pending=p;
            try {if(!action.run(gatt)) p.future.completeExceptionally(new IOException("无法启动 "+kind));}
            catch(Exception e) {p.future.completeExceptionally(e);}
        }
        return await(p,20);
    }
    public void connect(BluetoothDevice device) throws Exception {
        disconnect(null);
        Pending p=new Pending("connect","");
        synchronized(this) {
            pending=p;
            try {gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);}
            catch(Exception e) {pending=null;throw e;}
            if(gatt==null) {pending=null;throw new IOException("无法创建蓝牙连接");}
        }
        try {
            await(p,25);
            operation("services","",BluetoothGatt::discoverServices);
            try {operation("mtu","",g->g.requestMtu(517));} catch(Exception e) {
                if(gatt==null) throw e;
                listener.log("MTU 协商未成功，使用 "+mtu+": "+e.getMessage());
            }
            synchronized(this) {
                if(gatt==null) throw new IOException("连接已断开");
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); ready=true;
            }
        } catch(Exception e) {disconnect(null);throw e;}
    }
    public synchronized void disconnect(String reason) {
        BluetoothGatt old=gatt; gatt=null; ready=false; mtu=23;
        if(pending!=null) {pending.future.completeExceptionally(new IOException("连接已关闭"));pending=null;}
        Inbox box=inbox; if(box!=null) {box.messages.clear();box.messages.offer(new IOException("连接已关闭"));}
        subscriptions.clear();
        if(old!=null) {
            try {old.disconnect();}catch(RuntimeException ignored){}
            try {old.close();}catch(RuntimeException ignored){}
        }
        if(reason!=null) listener.disconnected(reason);
    }
    public void write(BluetoothGattCharacteristic c,byte[] value,boolean response) throws Exception {
        int property=response?BluetoothGattCharacteristic.PROPERTY_WRITE:BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
        if((c.getProperties()&property)==0) throw new IOException("该特征不支持所选写入方式");
        if(value.length>payloadLimit()) throw new IOException("单次写入最多 "+payloadLimit()+" 字节");
        int type=response?BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT:BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        operation("write",key(c),g->{
            if(Build.VERSION.SDK_INT>=33) return g.writeCharacteristic(c,value,type)==BluetoothStatusCodes.SUCCESS;
            c.setWriteType(type);c.setValue(value);return g.writeCharacteristic(c);
        });
    }
    /** mode: 0=off, 1=notify, 2=indicate. Restores the previous state on failure. */
    public void subscribe(BluetoothGattCharacteristic c,int mode) throws Exception {
        if(mode==subscription(c)) return;
        int property=mode==2?BluetoothGattCharacteristic.PROPERTY_INDICATE:BluetoothGattCharacteristic.PROPERTY_NOTIFY;
        if(mode!=0 && (c.getProperties()&property)==0) throw new IOException("该特征不支持所选订阅方式");
        BluetoothGattDescriptor d=c.getDescriptor(CCCD);
        if(d==null) throw new IOException("未找到 CCCD (0x2902)");
        BluetoothGatt g=gatt;
        if(g==null || !g.setCharacteristicNotification(c,mode!=0)) throw new IOException("无法设置通知");
        byte[] value=mode==0?BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE:mode==2?BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        try {
            operation("descriptorWrite",key(d),connection->{
                if(Build.VERSION.SDK_INT>=33) return connection.writeDescriptor(d,value)==BluetoothStatusCodes.SUCCESS;
                d.setValue(value);return connection.writeDescriptor(d);
            });
            subscriptions.put(key(c),mode);
        } catch(Exception e) {if(g==gatt)g.setCharacteristicNotification(c,subscription(c)!=0);throw e;}
    }
    /** A single inbox stays active across every frame in a file upload window. */
    public synchronized ProtocolSession openSession(BluetoothGattCharacteristic tx,BluetoothGattCharacteristic rx) throws IOException {
        if(!ready) throw new IOException("未连接设备");
        if(subscription(rx)==0) throw new IOException("请先订阅协议响应特征");
        if(inbox!=null) throw new IOException("另一个协议操作正在进行");
        Inbox box=new Inbox(key(rx)); inbox=box;
        return new ProtocolSession(tx,box);
    }
    public final class ProtocolSession implements FileUploader.Transport,AutoCloseable {
        private final BluetoothGattCharacteristic tx;
        private final Inbox box;
        private boolean sent;
        private ProtocolSession(BluetoothGattCharacteristic tx,Inbox box) {this.tx=tx;this.box=box;}
        @Override public void send(byte[] frame) throws Exception {
            if(inbox!=box || !ready) throw new IOException("协议会话已关闭");
            boolean response=(tx.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)==0;
            int fragmentSize=payloadLimit();
            for(int offset=0;offset<frame.length;offset+=fragmentSize) {
                sent=true;
                write(tx,Arrays.copyOfRange(frame,offset,Math.min(frame.length,offset+fragmentSize)),response);
            }
        }
        @Override public boolean hasResponse() {return !box.messages.isEmpty();}
        @Override public FileUploader.Response receive(long deadline) throws Exception {
            Object next=box.messages.poll(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if(next instanceof Exception) throw (Exception)next;
            return (FileUploader.Response)next;
        }
        @Override public void close() {synchronized(BleClient.this) {if(inbox==box)inbox=null;}}
    }
    public Protocol.Message transact(BluetoothGattCharacteristic tx,BluetoothGattCharacteristic rx,byte[] frame,int timeout,int... expected) throws Exception {
        try(ProtocolSession session=openSession(tx,rx)) {
            try {
                session.send(frame);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout);
                while(true) {
                    FileUploader.Response response=session.receive(deadline);
                    if(response==null || response.arrivedAt>deadline) {
                        disconnect("协议响应超时，已断开连接以清除未完成请求");
                        throw new IOException("等待设备响应超时");
                    }
                    Protocol.Message m=response.message;
                    if(m.type==Protocol.FAILURE) throw new IOException(Protocol.describe(m));
                    for(int type:expected) if(m.type==type) return m;
                    listener.log("异步协议消息: "+Protocol.describe(m));
                }
            } catch(Exception e) {
                // A partially transmitted frame must never be followed by an unrelated request.
                if(session.sent && !(e instanceof IOException && e.getMessage()!=null && e.getMessage().startsWith("Failure "))) disconnect(null);
                throw e;
            }
        }
    }
    private void notification(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value) {
        if(g!=gatt) return;
        long arrivedAt=System.nanoTime();
        byte[] copy=value.clone(); Inbox box=inbox;
        if(box!=null && box.key.equals(key(c))) {
            try {
                synchronized(box) {
                    for(Protocol.Message m:box.decoder.feed(copy)) if(!box.messages.offer(new FileUploader.Response(m,arrivedAt))) {
                        box.messages.clear();box.messages.offer(new IOException("协议响应队列溢出"));break;
                    }
                }
            } catch(RuntimeException e) {box.messages.clear();box.messages.offer(new IOException("无效协议响应",e));}
        }
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state) {
            synchronized(BleClient.this) {
                if(g!=gatt) return;
                if(state==BluetoothProfile.STATE_CONNECTED && status==BluetoothGatt.GATT_SUCCESS) complete(g,"connect","",status,null);
                else if(state==BluetoothProfile.STATE_DISCONNECTED || status!=BluetoothGatt.GATT_SUCCESS) disconnect("设备已断开，GATT status="+status);
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status) {complete(g,"services","",status,null);}
        @Override public void onMtuChanged(BluetoothGatt g,int value,int status) {
            synchronized(BleClient.this) {if(g==gatt && status==BluetoothGatt.GATT_SUCCESS) mtu=Math.max(23,Math.min(517,value));complete(g,"mtu","",status,null);}
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status) {complete(g,"write",key(c),status,null);}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status) {complete(g,"descriptorWrite",key(d),status,null);}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) {if(Build.VERSION.SDK_INT<33)notification(g,c,c.getValue());}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value) {notification(g,c,value);}
    };
}

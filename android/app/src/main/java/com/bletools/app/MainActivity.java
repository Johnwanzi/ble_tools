package com.bletools.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@SuppressLint({"MissingPermission", "SetTextI18n"})
public final class MainActivity extends Activity implements BleClient.Listener {
    private static final int PERMISSIONS=100, ENABLE_BT=101, PICK_FILE=102;
    private static final int TEAL=0xff087f72, INK=0xff182d35, MUTED=0xff577078;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<String,ScanResult> devices=new HashMap<>();
    private final List<BluetoothGattCharacteristic> writes=new ArrayList<>(), receives=new ArrayList<>();
    private final Protocol protocol=new Protocol();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BleClient ble;
    private boolean scanning,busy,pickingFile,destroyed;
    private volatile boolean cancelled;
    private long scanEpoch;
    private Runnable pendingPermissionAction;
    private TextView connection,scanStatus,connectionDetail,resultView,fileLabel,progressLabel;
    private EditText filter,rssi,pingInput,pathInput,chunkInput,runsInput;
    private Spinner txSpinner,rxSpinner,windowSpinner;
    private Button scanButton;
    private ProgressBar progress;
    private LinearLayout deviceList,channelPanel;
    private ViewFlipper pages;
    private final List<Button> navButtons=new ArrayList<>();
    private File uploadFile;
    private String uploadName="";
    private final Runnable scanRefresh=new Runnable() {
        @Override public void run() {if(scanning) {renderDevices();main.postDelayed(this,800);}}
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BluetoothManager manager=getSystemService(BluetoothManager.class);
        adapter=manager==null?null:manager.getAdapter();
        ble=new BleClient(this,this);
        buildUi();
        log("BLE Tool 1.3.0 · 连接设备后，可使用 Ping 和文件传输。");
        if(adapter==null) log("此设备不支持蓝牙。");
    }
    private int dp(int n) {return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column() {LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView label(LinearLayout parent,String text,int size,int color) {
        TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(6),0,dp(6));
        parent.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;
    }
    private void heading(LinearLayout parent,String text) {label(parent,text,18,INK).setTypeface(null,Typeface.BOLD);}
    private EditText input(LinearLayout parent,String title,String value,boolean numeric) {
        label(parent,title,12,MUTED);EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(14);
        e.setInputType(numeric?android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED:android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(value);parent.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;
    }
    private LinearLayout row(LinearLayout parent) {LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);parent.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
    private Button button(LinearLayout parent,String title,Runnable action) {
        Button b=new Button(this);b.setText(title);b.setTextSize(13);b.setAllCaps(false);b.setMinWidth(0);
        b.setOnClickListener(v->{try{action.run();}catch(Exception e){error(e);}});
        if(parent.getOrientation()==LinearLayout.HORIZONTAL)parent.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));
        else parent.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;
    }
    private Spinner spinner(LinearLayout parent,String title,String... items) {
        label(parent,title,12,MUTED);Spinner s=new Spinner(this);parent.addView(s,new LinearLayout.LayoutParams(-1,dp(52)));setItems(s,Arrays.asList(items));return s;
    }
    private void setItems(Spinner s,List<String> items) {
        ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);
    }
    private LinearLayout page() {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout body=column();body.setPadding(dp(18),dp(10),dp(18),dp(24));scroll.addView(body);pages.addView(scroll);return body;
    }
    private void buildUi() {
        LinearLayout root=column();root.setBackgroundColor(0xfff3f7f7);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;
        });
        LinearLayout header=column();header.setPadding(dp(18),dp(8),dp(18),0);root.addView(header);
        label(header,"BLE Tool",25,INK).setTypeface(null,Typeface.BOLD);
        connection=label(header,"未连接 · Ping / 文件传输",12,MUTED);
        LinearLayout nav=row(root);String[] names={"连接","Ping","文件传输"};
        for(int i=0;i<names.length;i++){final int n=i;navButtons.add(button(nav,names[i],()->showPage(n)));}
        pages=new ViewFlipper(this);root.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        buildScan(page());buildPing(page());buildTransfer(page());
        setContentView(root);root.requestApplyInsets();showPage(0);
    }
    private void showPage(int index) {pages.setDisplayedChild(index);for(int i=0;i<navButtons.size();i++)navButtons.get(i).setTextColor(i==index?TEAL:MUTED);}
    private void buildScan(LinearLayout body) {
        heading(body,"附近的设备");label(body,"按信号强度排序，点击设备即可连接。",13,MUTED);
        filter=input(body,"筛选名称 / MAC 地址","",false);rssi=input(body,"最低 RSSI (dBm，-127 至 0)","-100",true);
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){renderDevices();}public void afterTextChanged(Editable e){}};
        filter.addTextChangedListener(watcher);rssi.addTextChangedListener(watcher);
        LinearLayout controls=row(body);scanButton=button(controls,"开始扫描",()->{if(scanning)stopScan();else withBluetooth(this::startScan);});
        button(controls,"断开连接",this::disconnect);
        connectionDetail=label(body,"扫描并选择要连接的设备。",13,MUTED);
        channelPanel=column();body.addView(channelPanel);
        heading(channelPanel,"通信通道");
        label(channelPanel,"Ping 和文件传输共用以下通道；有多个通道时请选择设备对应的特征。",13,MUTED);
        txSpinner=spinner(channelPanel,"写入特征");rxSpinner=spinner(channelPanel,"响应特征 (Notify / Indicate)");
        txSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){refreshReceives();}public void onNothingSelected(AdapterView<?> p){}});
        channelPanel.setVisibility(View.GONE);
        scanStatus=label(body,"尚未扫描",13,MUTED);deviceList=column();body.addView(deviceList);
    }
    private void buildPing(LinearLayout body) {
        heading(body,"Ping 测试");
        label(body,"发送消息并查看设备响应。通信通道可在「连接」页选择。",13,MUTED);
        pingInput=input(body,"Ping 消息","Hello from Android",false);
        button(body,"发送 Ping",this::sendPing);
        heading(body,"Ping 结果");
        resultView=label(body,"尚未发送 Ping",13,INK);resultView.setTypeface(Typeface.MONOSPACE);resultView.setTextIsSelectable(true);
    }
    private void buildTransfer(LinearLayout body) {
        heading(body,"文件传输");
        label(body,"将手机文件上传到设备。上传过程中请保持应用在前台。",13,MUTED);
        fileLabel=label(body,"未选择文件",13,MUTED);
        button(body,"选择手机中的文件",()->{
            if(busy)throw new IllegalStateException("请等待当前操作完成");
            pickingFile=true;
            try {startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),PICK_FILE);}
            catch(RuntimeException e){pickingFile=false;throw e;}
        });
        pathInput=input(body,"设备保存路径","vol0:test.bin",false);
        chunkInput=input(body,"每个协议包的数据字节数 (16–2048)","1800",true);
        windowSpinner=spinner(body,"发送窗口 N（最多未确认块数）","1","2","3","4","5");
        windowSpinner.setContentDescription("发送窗口 N");
        windowSpinner.setSelection(FileUploader.DEFAULT_WINDOW-1);
        runsInput=input(body,"重复上传次数 (1–10000)","1",true);
        LinearLayout upload=row(body);button(upload,"开始上传",this::startUpload);button(upload,"取消并断开",this::disconnect);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);body.addView(progress);
        progressLabel=label(body,"就绪",12,MUTED);
    }
    private String required(EditText input) {String v=input.getText().toString().trim();if(v.isEmpty())throw new IllegalArgumentException("路径不能为空");return v;}
    private int integer(EditText input,int min,int max) {
        try {int n=Integer.parseInt(input.getText().toString());if(n>=min && n<=max)return n;}catch(NumberFormatException ignored){}
        throw new IllegalArgumentException("请输入 "+min+" 至 "+max+" 之间的整数");
    }
    private void withBluetooth(Runnable action) {
        if(adapter==null)throw new IllegalStateException("此设备不支持蓝牙");
        String[] permissions=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        List<String> missing=new ArrayList<>();for(String p:permissions)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
        if(!missing.isEmpty()){pendingPermissionAction=action;requestPermissions(missing.toArray(new String[0]),PERMISSIONS);return;}
        if(!adapter.isEnabled()){pendingPermissionAction=action;startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BT);return;}
        action.run();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=PERMISSIONS)return;
        Runnable action=pendingPermissionAction;pendingPermissionAction=null;
        boolean granted=results.length>0;for(int r:results)granted &= r==PackageManager.PERMISSION_GRANTED;
        if(granted && action!=null){try{withBluetooth(action);}catch(Exception e){error(e);}}
        else new AlertDialog.Builder(this).setTitle("需要蓝牙权限").setMessage("请允许附近设备权限；Android 8–11 扫描还需要位置权限。可在应用设置中重新开启。")
            .setNegativeButton("关闭",null).setPositiveButton("应用设置",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).show();
    }
    private void startScan() {
        if(busy)throw new IllegalStateException("请等待当前操作完成");
        if(Build.VERSION.SDK_INT<31) {
            LocationManager location=getSystemService(LocationManager.class);
            boolean enabled=location!=null && (Build.VERSION.SDK_INT>=28?location.isLocationEnabled():location.isProviderEnabled(LocationManager.GPS_PROVIDER)||location.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            if(!enabled){new AlertDialog.Builder(this).setTitle("请开启系统定位服务").setMessage("Android 8–11 的 BLE 扫描需要系统定位开关开启。")
                .setNegativeButton("取消",null).setPositiveButton("打开设置",(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).show();return;}
        }
        stopScan();integer(rssi,-127,0);devices.clear();renderDevices();scanner=adapter.getBluetoothLeScanner();
        if(scanner==null)throw new IllegalStateException("蓝牙扫描器不可用");
        scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
        scanning=true;scanButton.setText("停止扫描");scanStatus.setText("正在扫描 · 30 秒后自动停止");
        long epoch=++scanEpoch;main.post(scanRefresh);main.postDelayed(()->{if(scanning && epoch==scanEpoch)stopScan();},30000);log("开始扫描");
    }
    private void stopScan() {
        ++scanEpoch;main.removeCallbacks(scanRefresh);
        if(scanner!=null && scanning){try{scanner.stopScan(scanCallback);}catch(RuntimeException e){log("停止扫描: "+e.getMessage());}}
        if(scanning)log("扫描已停止，共发现 "+devices.size()+" 个设备");
        scanning=false;if(scanButton!=null)scanButton.setText("开始扫描");if(scanStatus!=null)renderDevices();
    }
    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult result){main.post(()->{if(scanning)devices.put(result.getDevice().getAddress(),result);});}
        @Override public void onBatchScanResults(List<ScanResult> results){for(ScanResult r:results)onScanResult(0,r);}
        @Override public void onScanFailed(int code){main.post(()->{stopScan();error(new IOException("扫描失败，错误码="+code+"，请稍后重试"));});}
    };
    private String deviceName(ScanResult r) {
        String name=r.getScanRecord()==null?null:r.getScanRecord().getDeviceName();
        if(name==null){try{name=r.getDevice().getName();}catch(SecurityException ignored){}}
        return name==null||name.isEmpty()?"未命名设备":name;
    }
    private void renderDevices() {
        if(deviceList==null)return;
        String needle=filter.getText().toString().toLowerCase(Locale.ROOT);int threshold=-127;
        try{threshold=Integer.parseInt(rssi.getText().toString());}catch(NumberFormatException ignored){}
        List<ScanResult> sorted=new ArrayList<>(devices.values());sorted.sort((a,b)->Integer.compare(b.getRssi(),a.getRssi()));
        deviceList.removeAllViews();int shown=0;
        for(ScanResult r:sorted) {
            String name=deviceName(r),address=r.getDevice().getAddress();
            if(r.getRssi()<threshold || !(name+address).toLowerCase(Locale.ROOT).contains(needle))continue;
            shown++;Button b=button(deviceList,name+"   "+r.getRssi()+" dBm\n"+address,()->withBluetooth(()->connect(r.getDevice(),name)));
            b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setTextColor(r.getRssi()>=-60?TEAL:r.getRssi()>=-80?0xff976817:0xffb84840);
        }
        scanStatus.setText((scanning?"扫描中":"扫描已停止")+" · 显示 "+shown+" / "+devices.size()+" 个设备");
    }
    private void connect(BluetoothDevice device,String name) {
        if(busy)throw new IllegalStateException("请等待当前操作完成");stopScan();clearChannels();
        connection.setText("正在连接 "+name);
        runJob("连接 "+name,()->{ble.connect(device);if(cancelled){ble.disconnect(null);return;}main.post(()->{if(ble.isReady()){populateChannels();showPage(1);}});},false);
    }
    private void clearChannels() {
        writes.clear();receives.clear();setItems(txSpinner,Collections.emptyList());setItems(rxSpinner,Collections.emptyList());
        channelPanel.setVisibility(View.GONE);
    }
    private String charLabel(BluetoothGattCharacteristic c) {return c.getUuid()+" ["+c.getService().getInstanceId()+":"+c.getInstanceId()+"]";}
    private void populateChannels() {
        clearChannels();List<String> labels=new ArrayList<>();
        for(BluetoothGattService service:ble.services()) {
            boolean hasResponse=false;
            for(BluetoothGattCharacteristic c:service.getCharacteristics())
                if((c.getProperties()&(BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE))!=0)hasResponse=true;
            if(!hasResponse)continue;
            for(BluetoothGattCharacteristic c:service.getCharacteristics()) {
                if((c.getProperties()&(BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))!=0){writes.add(c);labels.add(charLabel(c));}
            }
        }
        setItems(txSpinner,labels);refreshReceives();channelPanel.setVisibility(View.VISIBLE);
        log(writes.isEmpty()?"此设备没有可用于 Ping / 文件传输的写入与响应通道。":"已连接。请确认通信通道后发送 Ping 或上传文件。");
    }
    private void refreshReceives() {
        receives.clear();List<String> names=new ArrayList<>();int i=txSpinner.getSelectedItemPosition();
        if(i>=0 && i<writes.size()) for(BluetoothGattCharacteristic c:writes.get(i).getService().getCharacteristics()) {
            if((c.getProperties()&(BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE))!=0){receives.add(c);names.add(charLabel(c));}
        }
        setItems(rxSpinner,names);
    }
    private BluetoothGattCharacteristic[] channels() {
        if(!ble.isReady())throw new IllegalStateException("请先连接设备");
        int a=txSpinner.getSelectedItemPosition(),b=rxSpinner.getSelectedItemPosition();
        if(a<0||a>=writes.size()||b<0||b>=receives.size())throw new IllegalStateException("请选择写入和响应特征");
        return new BluetoothGattCharacteristic[]{writes.get(a),receives.get(b)};
    }
    private interface Job {void run() throws Exception;}
    private interface SessionJob {void run(BluetoothGattCharacteristic tx,BluetoothGattCharacteristic rx) throws Exception;}
    private void protocolJob(String name,SessionJob job) {
        BluetoothGattCharacteristic[] ch=channels();
        runJob(name,()->{
            int previous=ble.subscription(ch[1]);
            if(previous==0)ble.subscribe(ch[1],(ch[1].getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0?1:2);
            try{job.run(ch[0],ch[1]);}
            finally{if(ble.isReady() && previous==0)try{ble.subscribe(ch[1],0);}catch(Exception e){log("恢复订阅失败: "+e.getMessage());}}
        });
    }
    private void sendPing() {
        byte[] data=Protocol.string(1,pingInput.getText().toString());
        protocolJob("Ping",(tx,rx)->{
            long start=System.nanoTime();
            Protocol.Message message=ble.transact(tx,rx,protocol.frame(Protocol.PING,data,0,1),5,Protocol.SUCCESS);
            String result=Protocol.describe(message)+String.format(Locale.ROOT,"\n耗时 %.0f ms",(System.nanoTime()-start)/1e6);
            log("Ping 成功");main.post(()->resultView.setText(result));
        });
        resultView.setText("等待设备响应…");
    }
    private void startUpload() {
        if(uploadFile==null || !uploadFile.isFile())throw new IllegalStateException("请先选择上传文件");
        File file=uploadFile;String path=required(pathInput);int chunk=integer(chunkInput,16,2048),runs=integer(runsInput,1,10000);
        int window=windowSpinner.getSelectedItemPosition()+1;
        protocolJob("文件上传",(tx,rx)->{
            long total=file.length();if(total==0)throw new IOException("不支持上传空文件");
            log("开始上传：发送窗口 "+window+" 块，分块 "+chunk+" B，重复 "+runs+" 次");
            try(RandomAccessFile input=new RandomAccessFile(file,"r")) {
                for(int run=1;run<=runs;run++) {
                    final int runIndex=run;
                    try(BleClient.ProtocolSession session=ble.openSession(tx,rx)) {
                        FileUploader.upload(input,path,chunk,window,protocol,session,()->cancelled,
                                (acknowledged,size,speed,rtt)->updateProgress((int)(acknowledged*100/size),
                                        String.format(Locale.ROOT,"[%d/%d] %d / %d B · %.1f KB/s · RTT %.0f ms",
                                                runIndex,runs,acknowledged,size,speed/1024.0,rtt)));
                    } catch(Exception e) {
                        // Other blocks may still be outstanding even after a Failure ACK.
                        ble.disconnect(null);
                        throw e;
                    }
                    log("上传完成 ["+run+"/"+runs+"]: "+path+" · "+total+" B");
                    if(run==runs)updateProgress(100,"上传完成 · "+runs+" 次 · 每次 "+total+" B");
                    if(run<runs)for(int i=0;i<10;i++){if(cancelled)throw new IOException("上传已取消");Thread.sleep(100);}
                }
            }
        });
    }
    private void updateProgress(int value,String text) {main.post(()->{progress.setProgress(value);progressLabel.setText(text);});}
    private void runJob(String name,Job job) {runJob(name,job,true);}
    private void runJob(String name,Job job,boolean needsConnection) {
        if(busy)throw new IllegalStateException("正在执行其他操作，请等待完成");
        if(needsConnection && !ble.isReady())throw new IllegalStateException("请先连接设备");
        busy=true;cancelled=false;windowSpinner.setEnabled(false);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);connection.setText(name+"…");
        worker.execute(()->{
            try{if(!cancelled)job.run();}
            catch(Exception e){log(name+"失败: "+e.getMessage());main.post(()->{if(name.equals("Ping"))resultView.setText("Ping 失败: "+e.getMessage());else if(name.contains("文件"))progressLabel.setText(name+"失败: "+e.getMessage());});}
            finally{main.post(()->{busy=false;if(destroyed)return;windowSpinner.setEnabled(true);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);connection.setText(ble.isReady()?"已连接 · MTU "+ble.mtu():"未连接");if(!ble.isReady())clearChannels();});}
        });
    }
    private void disconnect() {
        cancelled=true;stopScan();ble.disconnect(null);
        if(!worker.isShutdown())worker.execute(()->ble.disconnect(null));
        clearChannels();connection.setText("未连接");if(busy)updateProgress(progress.getProgress(),"操作已取消；已断开连接");log("已释放蓝牙连接");
    }
    @Override public void disconnected(String reason) {main.post(()->{cancelled=true;clearChannels();connection.setText("未连接");log(reason);});}
    @Override public void log(String line) {
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->log(line));return;}
        if(destroyed)return;
        if(connectionDetail!=null)connectionDetail.setText(line);
    }
    private void error(Exception e) {log(e.getMessage()==null?e.toString():e.getMessage());Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==ENABLE_BT){Runnable action=pendingPermissionAction;pendingPermissionAction=null;if(result==RESULT_OK && action!=null)try{withBluetooth(action);}catch(Exception e){error(e);}return;}
        if(request==PICK_FILE)pickingFile=false;
        if(result!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        if(request==PICK_FILE) {
            try{runJob("读取文件",()->stageFile(uri),false);}catch(Exception e){error(e);}
        }
    }
    private void stageFile(Uri uri) throws Exception {
        String name="upload.bin";
        try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null && cursor.moveToFirst())name=cursor.getString(0);}
        File staged=File.createTempFile("ble-upload-",".tmp",getCacheDir());boolean ok=false;
        try {
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(staged)) {
                if(in==null)throw new IOException("无法读取文件");byte[] buffer=new byte[32768];long count=0;int n;
                while((n=in.read(buffer))!=-1){if(cancelled)throw new IOException("文件读取已取消");count+=n;if(count>512L*1024*1024)throw new IOException("文件不能超过 512 MiB");out.write(buffer,0,n);}
                if(count==0)throw new IOException("请选择非空文件");
            }
            if(uploadFile!=null && !uploadFile.delete())log("旧临时文件将在清理缓存时删除");uploadFile=staged;uploadName=name;ok=true;
            String display=uploadName+" · "+staged.length()+" B";main.post(()->fileLabel.setText(display));log("已选择 "+display);
        }finally{if(!ok && !staged.delete())log("临时文件将在清理缓存时删除");}
    }
    private final BroadcastReceiver bluetoothState=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction()) && intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1)==BluetoothAdapter.STATE_OFF)disconnect();
        }
    };
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);if(Build.VERSION.SDK_INT>=33)registerReceiver(bluetoothState,f,Context.RECEIVER_EXPORTED);else registerReceiver(bluetoothState,f);}
    @Override protected void onStop(){super.onStop();unregisterReceiver(bluetoothState);stopScan();if(!pickingFile && (ble.isReady()||busy))disconnect();}
    @Override protected void onDestroy(){destroyed=true;cancelled=true;ble.disconnect(null);worker.shutdownNow();main.removeCallbacksAndMessages(null);if(uploadFile!=null)uploadFile.delete();super.onDestroy();}
}

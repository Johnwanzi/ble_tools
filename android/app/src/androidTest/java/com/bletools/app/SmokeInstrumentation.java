package com.bletools.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicReference;

/** Device/emulator smoke test without a third-party test runtime. Does not write to BLE devices. */
public final class SmokeInstrumentation extends Instrumentation {
    private Activity activity;
    private int checks;
    @Override public void onCreate(Bundle args) {super.onCreate(args);start();}
    private View find(View root,String text) {
        if(root instanceof TextView && ((TextView)root).getText().toString().equals(text) && root.isShown())return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=find(((ViewGroup)root).getChildAt(i),text);if(v!=null)return v;}
        return null;
    }
    private void ui(Runnable action) throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        runOnMainSync(()->{try{action.run();}catch(Throwable e){failure.set(e);}});waitForIdleSync();
        if(failure.get()!=null)throw new Exception(failure.get());
    }
    private void present(String text) throws Exception {ui(()->{checks++;if(find(activity.getWindow().getDecorView(),text)==null)throw new AssertionError("Missing view: "+text);});}
    private void absent(String text) throws Exception {ui(()->{checks++;if(find(activity.getWindow().getDecorView(),text)!=null)throw new AssertionError("Unexpected feature: "+text);});}
    private void click(String text) throws Exception {ui(()->{View v=find(activity.getWindow().getDecorView(),text);if(!(v instanceof Button))throw new AssertionError("Missing button: "+text);v.performClick();});}
    private Spinner findWindow(View root) {
        if(root instanceof Spinner && "发送窗口 N".contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return (Spinner)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){Spinner s=findWindow(((ViewGroup)root).getChildAt(i));if(s!=null)return s;}
        return null;
    }
    @Override public void onStart() {
        Bundle results=new Bundle();
        try {
            Intent intent=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=startActivitySync(intent);waitForIdleSync();
            present("BLE Tool");present("附近的设备");present("开始扫描");
            ui(()->{View v=find(activity.getWindow().getDecorView(),"-100");if(!(v instanceof EditText))throw new AssertionError("RSSI input");((EditText)v).setText("-75");checks++;});
            absent("GATT");absent("设备");absent("日志");
            click("Ping");present("Ping 测试");present("尚未发送 Ping");click("发送 Ping");present("Ping 测试");
            click("文件传输");present("未选择文件");present("开始上传");click("开始上传");present("未选择文件");
            present("发送窗口 N（最多未确认块数）");
            ui(()->{
                Spinner window=findWindow(activity.getWindow().getDecorView());
                checks++;if(window==null || window.getCount()!=5 || !"2".equals(window.getSelectedItem()))throw new AssertionError("Window must default to 2 with five choices");
                window.setSelection(4);checks++;if(!"5".equals(window.getSelectedItem()))throw new AssertionError("Window maximum 5");
                window.setSelection(0);checks++;if(!"1".equals(window.getSelectedItem()))throw new AssertionError("Window minimum 1");
                window.setSelection(1);
            });
            absent("执行固件更新");absent("设备信息");absent("设置亮度");absent("重启设备");
            click("连接");present("附近的设备");click("断开连接");present("未连接");
            // Verify activity recreation, including receiver/connection cleanup.
            ui(()->activity.finish());
            activity=startActivitySync(intent);waitForIdleSync();present("BLE Tool");present("附近的设备");
            results.putString("stream","PASS: "+checks+" UI checks (Ping/transfer navigation, upload window 1-5/default 2, input, disconnected guards, close/reopen).\n");
            finish(Activity.RESULT_OK,results);
        }catch(Throwable e){results.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,results);}
    }
}

package com.pdfstudio.offline;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER=4102;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        webView=new WebView(this); setContentView(webView);
        WebSettings s=webView.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(true); s.setAllowContentAccess(true);
        webView.addJavascriptInterface(new AndroidBridge(),"Android");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> cb,FileChooserParams params){
                if(filePathCallback!=null)filePathCallback.onReceiveValue(null); filePathCallback=cb;
                Intent intent=params.createIntent(); intent.addCategory(Intent.CATEGORY_OPENABLE);
                try{startActivityForResult(intent,FILE_CHOOSER);}catch(Exception e){filePathCallback=null;Toast.makeText(MainActivity.this,"File picker unavailable",Toast.LENGTH_SHORT).show();}
                return true;
            }
        });
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data); if(requestCode!=FILE_CHOOSER||filePathCallback==null)return;
        Uri[] result=null;
        if(resultCode==RESULT_OK&&data!=null){
            if(data.getClipData()!=null){int n=data.getClipData().getItemCount();result=new Uri[n];for(int i=0;i<n;i++)result[i]=data.getClipData().getItemAt(i).getUri();}
            else if(data.getData()!=null)result=new Uri[]{data.getData()};
        }
        filePathCallback.onReceiveValue(result); filePathCallback=null;
    }

    @Override public void onBackPressed(){if(webView.canGoBack())webView.goBack();else super.onBackPressed();}

    private class AndroidBridge {
        @JavascriptInterface public void saveBase64File(String name,String mime,String base64){new Thread(()->{try{saveBytes(name,mime,Base64.decode(base64,Base64.DEFAULT));toast("Saved to Downloads/PDF Studio: "+name);}catch(Exception e){toast("Save failed: "+e.getMessage());}}).start();}
        @JavascriptInterface public void renderPdfToImages(String base64,String pagesSpec,String format,double scale,int quality,String baseName){new Thread(()->{
            File tmp=null; try{
                byte[] data=Base64.decode(base64,Base64.DEFAULT); tmp=File.createTempFile("pdfstudio-",".pdf",getCacheDir()); try(FileOutputStream fos=new FileOutputStream(tmp)){fos.write(data);}
                try(ParcelFileDescriptor pfd=ParcelFileDescriptor.open(tmp,ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer renderer=new PdfRenderer(pfd)){
                    List<Integer> pages=parsePages(pagesSpec,renderer.getPageCount()); int saved=0;
                    for(int pageIndex:pages){try(PdfRenderer.Page page=renderer.openPage(pageIndex)){
                        double sc=Math.max(1.0,Math.min(3.0,scale)); int w=Math.max(1,(int)Math.round(page.getWidth()*sc)),h=Math.max(1,(int)Math.round(page.getHeight()*sc));
                        Bitmap bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bmp.eraseColor(Color.WHITE);page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        boolean jpg="jpeg".equalsIgnoreCase(format);String ext=jpg?"jpg":"png",mime=jpg?"image/jpeg":"image/png",name=baseName+"-page-"+String.format(Locale.US,"%03d",pageIndex+1)+"."+ext;
                        ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,name);v.put(MediaStore.Downloads.MIME_TYPE,mime);v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/PDF Studio");
                        Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new Exception("Storage unavailable");
                        try(OutputStream os=getContentResolver().openOutputStream(uri)){if(os==null||!bmp.compress(jpg?Bitmap.CompressFormat.JPEG:Bitmap.CompressFormat.PNG,Math.max(10,Math.min(100,quality)),os))throw new Exception("Image save failed");}finally{bmp.recycle();}saved++;
                    }} toast(saved+" image"+(saved==1?"":"s")+" saved to Downloads/PDF Studio");
                }
            }catch(Exception e){toast("PDF to Image failed: "+e.getMessage());}finally{if(tmp!=null)tmp.delete();}
        }).start();}
    }

    private void saveBytes(String name,String mime,byte[] data)throws Exception{ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,name);v.put(MediaStore.Downloads.MIME_TYPE,mime);v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/PDF Studio");Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new Exception("Storage unavailable");try(OutputStream os=getContentResolver().openOutputStream(uri)){if(os==null)throw new Exception("Storage unavailable");os.write(data);}}
    private List<Integer> parsePages(String spec,int total)throws Exception{String s=spec==null?"all":spec.trim().toLowerCase(Locale.US);Set<Integer> out=new LinkedHashSet<>();if(s.isEmpty()||s.equals("all")){for(int i=0;i<total;i++)out.add(i);}else for(String raw:s.split(",")){String t=raw.trim();if(t.matches("\\d+"))addPage(out,Integer.parseInt(t),total);else if(t.matches("\\d+\\s*-\\s*\\d+")){String[] ab=t.split("-");int a=Integer.parseInt(ab[0].trim()),b=Integer.parseInt(ab[1].trim()),step=a<=b?1:-1;for(int n=a;;n+=step){addPage(out,n,total);if(n==b)break;}}else throw new Exception("Invalid page selection: "+t);}return new ArrayList<>(out);}
    private void addPage(Set<Integer> out,int oneBased,int total)throws Exception{if(oneBased<1||oneBased>total)throw new Exception("Page "+oneBased+" is outside 1–"+total);out.add(oneBased-1);}
    private void toast(String text){runOnUiThread(()->Toast.makeText(MainActivity.this,text,Toast.LENGTH_LONG).show());}
}

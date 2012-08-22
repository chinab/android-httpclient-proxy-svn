package com.gp.proxy.test;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRouteParams;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.Proxy;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ProxytestActivity extends Activity {
    protected static final String TAG = "HttpTestActivity";

    // Matches blank input, ips, and domain names
    private static final String HOSTNAME_REGEXP =
            "^$|^[a-zA-Z0-9]+(\\-[a-zA-Z0-9]+)*(\\.[a-zA-Z0-9]+(\\-[a-zA-Z0-9]+)*)*$";
    private static final Pattern HOSTNAME_PATTERN;
    static {
        HOSTNAME_PATTERN = Pattern.compile(HOSTNAME_REGEXP);
    }
    
	static final int CONNECT_TIMEOUT = 10000;
	static final int SOCKET_TIMEOUT = 10000;
	
    private final int MSG_DOWNLOAD_START = 0;
    private final int MSG_DOWNLOAD_RUN = 1;
    private final int MSG_CONNECT_FAIL = 2;
    private final int MSG_DOWNLOAD_FAIL = 3;
    
    
	private EditText mFileUrlField;
    private EditText mHostnameField;
    private EditText mPortField;
    
    Button      mOKButton;
    Button      mClearButton;
    
    private final String mDefaultUrl = "http://download.microsoft.com/download/1/6/1/16174D37-73C1-4F76-A305-902E9D32BAC9/IE8-WindowsXP-x86-CHS.exe";
    private final String mDefaultProxyHost = "72.246.132.10";
    private final String mDefaultProxyPort = "80";
    
    private String mFileUrl;
    private String mHostname;
    private String mPort;
    
    private String mSaveFilepath;
    private DownloadDialog mDownloadDialog = null;
    private Thread mDownloadThread = null;
    private boolean mIsBreak = false;
    private Handler mUiHandler = null;
    
	private int mFilesize = 0;
	private int mDownloadSize = 0;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        init_views();
        createUiHander();
    }
    
    private void init_views() {
        mOKButton = (Button)findViewById(R.id.action);
        mOKButton.setOnClickListener(mOKHandler);

        mClearButton = (Button)findViewById(R.id.clear);
        mClearButton.setOnClickListener(mClearHandler);
        
        mFileUrlField = (EditText)findViewById(R.id.file_url);
        mHostnameField = (EditText)findViewById(R.id.hostname);
        mPortField = (EditText)findViewById(R.id.port);
        
        mFileUrlField.setText(mDefaultUrl);
        mHostnameField.setText(mDefaultProxyHost);
        mPortField.setText(mDefaultProxyPort);
    }
    
    boolean checkValidate() {
    	mFileUrl = mFileUrlField.getText().toString().trim();
    	if (mFileUrl == null || "".equals(mFileUrl)) {
    		Log.w(TAG, "Get empty file url");
    		return false;
    	}
    	
    	Log.i(TAG, "File url : " + mFileUrl);
    	
    	mHostname = mHostnameField.getText().toString().trim();
    	mPort = mPortField.getText().toString().trim();
    	Log.i(TAG, "Hostname : " + mHostname);
    	Log.i(TAG, "Port : " + mPort);
    	
    	if (proxyValidate(mHostname, mPort) > 0) {
    		Log.w(TAG, "Get invalidate proxy");
    		return false;
    	}
    	
    	return true;
    }
    
    boolean genSaveFilepath(String url) {    	
        boolean hasSD = Environment.getExternalStorageState().equals( 
                android.os.Environment.MEDIA_MOUNTED); 
        String SDPATH = Environment.getExternalStorageDirectory().getPath(); 
        if (!hasSD) {
        	return false;
        }
        	
		int start = url.lastIndexOf("/");
		int end = url.length();
		String fname = null;
		if (start != -1 && end != -1) {
			fname = url.substring(start + 1, end);
		}
		
		if (fname == null){
			fname = "Index.html";
		} 
		
		String path = SDPATH + "/" + "ProxyTest";
		File dir = new File(path); 
		if (!dir.exists()) {
			dir.mkdir();
		}
		
		mSaveFilepath = path + "/" + fname;
		Log.i(TAG, "Save file path : " + mSaveFilepath);
		return true;
    }
    
    public void createUiHander() {		
		mUiHandler = new Handler() {		
			/*UI handler for updater sub thread msg*/
	    	public void handleMessage (Message msg){
	    		switch(msg.what){
				case MSG_DOWNLOAD_START:
					if (mDownloadDialog != null) {
						mDownloadDialog.setCurrrentSize(mDownloadSize);
						mDownloadDialog.setMaxSize(mFilesize);
					}
					break;
				case MSG_DOWNLOAD_RUN:
					if (mDownloadDialog != null) {
						mDownloadDialog.setCurrrentSize(mDownloadSize);
					}
					break;	
				case MSG_CONNECT_FAIL:
					Toast.makeText(ProxytestActivity.this, "Connect fail!", Toast.LENGTH_LONG).show();
					break;
				case MSG_DOWNLOAD_FAIL:
					Toast.makeText(ProxytestActivity.this, "Download fail!", Toast.LENGTH_LONG).show();
					break;
				default:
					break;
	    		}
	    	}
		};
	}

	private void postUpdaterUiMsg(int msg) {
		/*notify that UI should be updated now*/
		Message uiMsg = new Message();
		uiMsg.what = msg;
		mUiHandler.sendMessage(uiMsg);		
	}
	
	void httpClientSetProxy(HttpParams httpParams) {		
        final String host = mHostname;
        final String portStr = mPort;
        int port = -1;
        if ("".equals(host) || "".equals(portStr)) {
        	Log.w(TAG, "Invalidate user proxy");
        	return;
        }
 
        try {
        	port = Integer.parseInt(portStr);
        } catch (Exception e) {
        	e.printStackTrace();
        }
        
        HttpHost proxy = new HttpHost(host, port);   
        httpParams.setParameter(ConnRouteParams.DEFAULT_PROXY, proxy);	
	}
	
    OnClickListener mOKHandler = new OnClickListener() {
    	@Override
        public void onClick(View v) {
    		if (!checkValidate()) {
    			Toast.makeText(ProxytestActivity.this, "Input is invalidate!", Toast.LENGTH_LONG).show();
    			return;
    		}
    		
    		if (!genSaveFilepath(mFileUrl)) {
    			Toast.makeText(ProxytestActivity.this, "SD card don't exist!", Toast.LENGTH_LONG).show();
    			return;    			
    		}
    		
    		showDownloadDialog();
    		
    		mDownloadThread = new Thread () {
        		public void run() {
        			
	                HttpResponse httpResponse;
	                HttpEntity httpEntity;	                
	                InputStream inputStream = null;
	                FileOutputStream fout = null;
	                
	                HttpParams httpParams = new BasicHttpParams();
	                httpClientSetProxy(httpParams);
	                HttpConnectionParams.setConnectionTimeout(httpParams, CONNECT_TIMEOUT);
				    HttpConnectionParams.setSoTimeout(httpParams, SOCKET_TIMEOUT);				    
	                
	                HttpClient httpClient = new DefaultHttpClient(httpParams);  
	                
	                mIsBreak = false;
	                try {
	                	HttpGet httpGet = new HttpGet(mFileUrl);  	            
	                	httpResponse = httpClient.execute(httpGet);
	                	if (httpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
		                    httpEntity = httpResponse.getEntity();
		                    inputStream = httpEntity.getContent();
		                    
		                    File saveFile = new File(mSaveFilepath);
		                    if (saveFile.exists()) {
		                    	saveFile.delete();
		                    }
		                    
		                    mDownloadSize = 0;
		                    mFilesize = (int)httpEntity.getContentLength();
		                    Log.d(TAG, "File size : " + mFilesize);
		                    postUpdaterUiMsg(MSG_DOWNLOAD_START);
		                    
		                    fout = new FileOutputStream(saveFile);
		                    byte[] buf = new byte[1024];
							int ch = -1;
							
		                    while((ch = inputStream.read(buf)) != 1){
		                        fout.write(buf, 0, ch);
		                        mDownloadSize += ch;
		                        
		                        postUpdaterUiMsg(MSG_DOWNLOAD_RUN);
		                        
		                        if (mIsBreak)
		                        	break;
		                    }
		                    
		                    httpClient.getConnectionManager().shutdown();
	                    } else {
	                    	postUpdaterUiMsg(MSG_CONNECT_FAIL);
	                    }	                   
	                } catch (Exception e) {
	                	e.printStackTrace();
	                	postUpdaterUiMsg(MSG_DOWNLOAD_FAIL);	                	
	                } finally{
	                	if (inputStream != null) {
		                    try {		                    	
		                    	inputStream.close();
		                    } catch (IOException e) {
		                        e.printStackTrace();
		                    }
	                    }
	                	
	                	if (fout != null) {
	                		try {
	                			fout.flush();
	                			fout.close();
	                		} catch (IOException e) {
		                        e.printStackTrace();
		                    }	                		
	                	}
	                	
	                	closeDownloadDialog();
	               }
        		}
            };
            
            if (mDownloadThread != null) {
            	mDownloadThread.start();
            }
        }       
    };
    
    OnClickListener mClearHandler = new OnClickListener() {
        public void onClick(View v) {
        	mFileUrlField.setText("");
            mHostnameField.setText("");
            mPortField.setText("");            
        }
    };
    
    /**
     * validate syntax of hostname and port entries
     * @return 0 on success, string resource ID on failure
     */
    public static int proxyValidate(String hostname, String port) {
        Matcher match = HOSTNAME_PATTERN.matcher(hostname);

        if (!match.matches()) return R.string.proxy_error_invalid_host;

        if (hostname.length() > 0 && port.length() == 0) {
            return R.string.proxy_error_empty_port;
        }

        if (port.length() > 0) {
            if (hostname.length() == 0) {
                return R.string.proxy_error_empty_host_set_port;
            }
            int portVal = -1;
            try {
                portVal = Integer.parseInt(port);
            } catch (NumberFormatException ex) {
                return R.string.proxy_error_invalid_port;
            }
            if (portVal <= 0 || portVal > 0xFFFF) {
                return R.string.proxy_error_invalid_port;
            }
        }
        return 0;
    }
    
    
    void showDownloadDialog() {
    	if (mDownloadDialog == null) {
    		mDownloadDialog = new DownloadDialog(ProxytestActivity.this);
    		mDownloadDialog.setCanceledOnTouchOutside(false);
    		mDownloadDialog.setCancelable(true);
    		mDownloadDialog.setTitle(getResources().getString(R.string.download_title));
    		mDownloadDialog.setOnCancelListener( new DialogInterface.OnCancelListener () {
					@Override
					public void onCancel(DialogInterface dialog) {
						Log.d(TAG, "Cancel download!");
						mIsBreak = true;
						if (mDownloadThread != null) {
							try {
								mDownloadThread.join();
							}
							catch (InterruptedException ex) {
								Log.e(TAG, "Wait thread break error");
							}
							mDownloadThread = null;	
						}						
						mDownloadDialog = null;
					}
    			});
    	}
    	
    	mDownloadDialog.show();
    }
    
	void closeDownloadDialog() {
		if (mDownloadDialog != null) {
			mDownloadDialog.cancel();
			mDownloadDialog.dismiss();		
			mDownloadDialog = null;
		}
	}
	
	class DownloadDialog extends Dialog implements
	android.view.View.OnClickListener {
		private int mMaxSize;
		private int mCurrentSize;
		
		private ProgressBar mPbar;
		private TextView mStatus;
		private TextView mProgress;  
		
		public DownloadDialog(Context context) {
			super(context);
			mMaxSize = 0;
			mCurrentSize = 0;
		}

		@Override
		public void onClick(View v) {
						
		} 
		
		@Override
		protected void onCreate(Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			setContentView(R.layout.download_layout);
			
			mProgress = (TextView) this.findViewById(R.id.down_tv);
			mPbar = (ProgressBar) this.findViewById(R.id.down_pb);
			mStatus = (TextView) this.findViewById(R.id.down_status);
			mStatus.setText("");
		}
		
		public void setCurrrentSize(int size) {
			mCurrentSize = size;
			mPbar.setProgress(size);
			updateStatus();
		}
		
		public void setMaxSize(int size) {
			mMaxSize = size;
			mPbar.setMax(size);
			updateStatus();
		}
		
		void updateStatus() {
			if (mMaxSize == 0)
				return;
			
			int percent = (int) ((double)mCurrentSize * 100 / mMaxSize);
			String str = getResources().getString(R.string.has_download); 
			mProgress.setText(str + percent + " %"); 
			
			String current = FormetFileSize(mCurrentSize);
			String max = FormetFileSize(mMaxSize);
			
			mStatus.setText(current + "/" + max);
		}
	}
	
	/**
	 * Format the file size 
	 * 
	 * @param fileSize
	 *        
	 * @return
	 */
	public String FormetFileSize(int fileSize) {
		DecimalFormat df = new DecimalFormat("#.00");
		String fileSizeString = "";
		if (fileSize < 1024) {
			fileSizeString = df.format((double) fileSize) + "B";
		} else if (fileSize < 1048576) {
			fileSizeString = df.format((double) fileSize / 1024) + "K";
		} else if (fileSize < 1073741824) {
			fileSizeString = df.format((double) fileSize / 1048576) + "M";
		} else {
			fileSizeString = df.format((double) fileSize / 1073741824) + "G";
		}
		return fileSizeString;
	}
}
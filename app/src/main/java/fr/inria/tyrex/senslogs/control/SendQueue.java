package fr.inria.tyrex.senslogs.control;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.webkit.MimeTypeMap;

import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;

import java.io.File;
import java.io.IOException;
import java.sql.Time;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import fr.inria.tyrex.senslogs.Application;
import fr.inria.tyrex.senslogs.R;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SendQueue extends Thread {
    private Map<Integer, File> filesToSend;
    private Map<Long, String> requestsToSend;

    private final Context mContext;

    private String webDavUsername = "";
    private String webDavPassword = "";
    private String webDavUrl = "";

    private Timer timer;
    private TimerTask timerTask = new TimerTask() {
        @Override
        public void run() {
            handleQueues();
        }
    };

    OkHttpClient client = new OkHttpClient();

    String getUrl(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .build();
        try (Response response = client.newCall(request).execute()) {
            return Objects.requireNonNull(response.body()).string();
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager
                = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    public static String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type;
    }

    public SendQueue(Context context) {
        mContext = context;
        webDavUsername = context.getResources().getString(R.string.webdav_username);
        webDavPassword = context.getResources().getString(R.string.webdav_password);
        webDavUrl = context.getResources().getString(R.string.webdav_url);

        this.filesToSend = new HashMap<>();
        this.requestsToSend = new HashMap<>();
    }

    public void addFileToQueue(Integer index, File file) {
        this.filesToSend.put(index, file);
    }

    public void addRequestToQueue(String url) {
        Long time = System.currentTimeMillis();
        this.requestsToSend.put(time, url);
    }

    private void clearFilesQueue(boolean deleteFiles) {
        this.filesToSend = new HashMap<>();
    }

    private void clearRequestsQueue() {
        this.requestsToSend = new HashMap<>();
    }

    @Override
    public void run() {
        super.run();
    }

    @Override
    public synchronized void start() {
        super.start();
        // Start a loop that will check every second if we have network and then process the queues
        if(timer != null) {
            return;
        }
        timer = new Timer();
        timer.scheduleAtFixedRate(timerTask, 0, 1000);
    }

    public void terminate(boolean isCancelled) {
        timer.cancel();
        timer = null;
        // Stop our loop and delete files
        this.interrupt();
    }

    private void sendFile(Integer index, File file) {
        Thread thread = new Thread(new Runnable(){
            @Override
            public void run() {
                try {
                    filesToSend.remove(index);
                    Sardine sardine = new OkHttpSardine();
                    sardine.setCredentials(webDavUsername, webDavPassword);
                    String filename = webDavUrl + "/" + file.getName();
                    try {
                        android.util.Log.d(Application.LOG_TAG, "SendQueue: sending file to webdav " + filename);
                        sardine.put(filename, file, getMimeType(filename));
                        if (file.delete())
                            android.util.Log.d(Application.LOG_TAG, "SendQueue: file " + file.toString() + " deleted for index " + index);
                    } catch (IOException e) {
                        //e.printStackTrace();
                        filesToSend.put(index, file);
                    }
                } catch (Exception e) {
                    //e.printStackTrace();
                    filesToSend.put(index, file);
                }
            }
        });
        thread.start();
    }

    private void sendRequest(Long timestamp, String url) {
        requestsToSend.remove(timestamp);
        String response = "";
        try {
            response = getUrl(url);
            android.util.Log.d(Application.LOG_TAG, "SendQueue: sendRequest response " + response);
        } catch (IOException e) {
            //e.printStackTrace();
            requestsToSend.put(timestamp, url);
        }
    }

    private void handleQueues() {
        if (requestsToSend.isEmpty() && filesToSend.isEmpty())
            return;
        if (!isNetworkAvailable()) {
            android.util.Log.d(Application.LOG_TAG, "SendQueue: no network, waiting...");
            return ;
        }
        android.util.Log.d(Application.LOG_TAG, "SendQueue: network found, processing queue");
        Map<Long, String> requests = new HashMap<>();
        for (Map.Entry<Long, String> item : requestsToSend.entrySet()) {
            requests.put(item.getKey(), item.getValue());
        }
        for (Map.Entry<Long, String> item : requests.entrySet()) {
            sendRequest(item.getKey(), item.getValue());
        }
        Map<Integer, File> files = new HashMap<>();
        for (Map.Entry<Integer, File> item : filesToSend.entrySet()) {
            files.put(item.getKey(), item.getValue());
        }
        for (Map.Entry<Integer, File> item : files.entrySet()) {
            sendFile(item.getKey(), item.getValue());
        }
    }
}

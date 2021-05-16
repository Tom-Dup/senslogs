package fr.inria.tyrex.senslogs.control;

import android.content.Context;
import android.util.Pair;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import androidx.core.content.ContextCompat;
import fr.inria.tyrex.senslogs.Application;
import fr.inria.tyrex.senslogs.model.FieldsWritableObject;
import fr.inria.tyrex.senslogs.model.log.Log;
import fr.inria.tyrex.senslogs.model.sensors.Sensor;

public class FlightRecorder {

    private final Context mContext;
    private final Recorder mRecorder;
    private RecorderWriter mRecorderWriter;
    private Log mLog;
    private final Recorder.RecorderListener mRecorderListener;
    private Timer timer;
    private TimerTask timerTask;
    private final int interval = 5000; // 10 Second
    private Integer iteration = 0;
    private String mainWorkingFolder;
    private Map<Integer, File> mWorkingFolders;
    private Set<Sensor> mSensors;
    private ZipCreationTask mZipCreationTask;

    public FlightRecorder(Context context, Recorder realRecorder) {
        mContext = context;
        mRecorder = realRecorder;
        mRecorderWriter = mRecorder.getRecorderWriter();
        mWorkingFolders = new HashMap<>();
        mRecorderListener = new Recorder.RecorderListener() {
            @Override
            public void onPlay() throws FileNotFoundException {
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: onPlay");
                mRecorderWriter = mRecorder.getRecorderWriter();
                mSensors = mRecorder.getSensors();
                mLog = mRecorder.getLog();
                if (iteration == 0) {
                    mainWorkingFolder = String.valueOf(UUID.randomUUID());
                    File mTemporaryFolder = new File(mContext.getFilesDir(), mainWorkingFolder);
                    if (!mTemporaryFolder.mkdir()) {
                        throw new FileNotFoundException();
                    }
                    mRecorderWriter.setCurrentFrWorkingFolder(mTemporaryFolder);
                }
                timer = new Timer();
                timerTask = createTask();
                timer.scheduleAtFixedRate(timerTask, 0, interval);
            }

            @Override
            public void onPause() {
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: onPause");
                timer.cancel();
                timerTask.cancel();
            }

            @Override
            public void onCancel() {
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: onCancel");
                timer.cancel();
                timerTask.cancel();
                // Close files pointer properly
                try {
                    if (iteration>1)
                        mRecorderWriter.frIterationSensorsFosClose(iteration-1);
                    mRecorderWriter.frIterationSensorsFosClose(iteration);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                deleteWorkingFolders();
                iteration = 0;
            }

            @Override
            public void onSave() {
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: onSave");
                timer.cancel();
                timerTask.cancel();
                save(iteration-1);
                save(iteration);
                //deleteWorkingFolders();
                iteration = 0;
            }
        };
        mRecorder.setListener(mRecorderListener);
    }

    private void deleteWorkingFolders() {
        for (Map.Entry<Integer, File> folder : mWorkingFolders.entrySet()) {
            File tmp = folder.getValue();
            if (tmp.isDirectory()) {
                String[] children = tmp.list();
                for (String child : children) {
                    if (new File(tmp, child).delete())
                        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: file " + child + " deleted in working folder " + tmp.toString());
                }
            }
            if (tmp.delete())
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: working folder " + tmp.toString() + " deleted");
        }
        File mTemporaryFolder = new File(mContext.getFilesDir(), mainWorkingFolder);
        if (mTemporaryFolder.delete())
            android.util.Log.d(Application.LOG_TAG, "FlightRecorder: main working folder " + mTemporaryFolder.toString() + " deleted");
    }

    private TimerTask createTask() {
        FlightRecorder flightRecorder = this;
        return new TimerTask() {
            @Override
            public void run() {
                try {
                    flightRecorder.run();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            }
            @Override
            public boolean cancel() {
                flightRecorder.cancel();
                return super.cancel();
            }
        };
    }

    private void run() throws FileNotFoundException {
        iteration++;
        if (iteration > 2) {
            save(iteration - 2);
        }
        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: timer run " + iteration.toString());
        // Create new folder for records
        File mTemporaryFolder = new File(mContext.getFilesDir() + "/" + mainWorkingFolder, iteration.toString());
        if (!mTemporaryFolder.mkdir()) {
            throw new FileNotFoundException();
        }
        mWorkingFolders.put(iteration, mTemporaryFolder);
        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: working folder = " + mTemporaryFolder.toString());
        mRecorderWriter.initFrIteration(iteration, mTemporaryFolder);
        for (Sensor sensor : mLog.getSensors()) {
            if (!(sensor instanceof FieldsWritableObject))
                continue;
            mRecorderWriter.createFile((FieldsWritableObject) sensor, true);
        }
    }

    private void save(Integer iterationToSave) {

        if (iterationToSave == 0)
            return;

        ContextCompat.getMainExecutor(mContext).execute(()  -> {
            android.util.Log.d(Application.LOG_TAG, "FlightRecorder: saving iteration " + iterationToSave.toString());
            // Close files pointer properly
            try {
                mRecorderWriter.frIterationSensorsFosClose(iterationToSave);
            } catch (IOException e) {
                e.printStackTrace();
            }
            // Create Zip File
            android.util.Log.d(Application.LOG_TAG, "FlightRecorder: create zip for iteration " + iterationToSave.toString());
            String filename = mainWorkingFolder + "-" + iterationToSave.toString();
            final Pair<File, ZipCreationTask> zipCreationPair;
            try {
                zipCreationPair = mRecorderWriter.createZipFile(filename, mLog, iterationToSave);
                final File zipFile = zipCreationPair.first;
                final ZipCreationTask zipTask = zipCreationPair.second;
                zipTask.addListener(new ZipCreationTask.ZipCreationListener() {
                    @Override
                    public void onProgress(File currentFile, float ratio) {
                    }
                    @Override
                    public void onTaskFinished(File outputFile, long fileSize) {
                        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: zip created for iteration " + iterationToSave.toString());
                        // Remove files when recorder finished
                        // TODO
                        zipTask.removeListener(this);
                        // Send file web server
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void cancel() {
        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: timer cancel");
    }
}


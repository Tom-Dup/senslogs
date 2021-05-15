package fr.inria.tyrex.senslogs.control;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.util.Pair;

import androidx.annotation.RequiresApi;

import org.ini4j.Wini;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.inria.tyrex.senslogs.Application;
import fr.inria.tyrex.senslogs.R;
import fr.inria.tyrex.senslogs.model.FieldsWritableObject;
import fr.inria.tyrex.senslogs.model.PositionReference;
import fr.inria.tyrex.senslogs.model.WritableObject;
import fr.inria.tyrex.senslogs.model.log.Log;
import fr.inria.tyrex.senslogs.model.sensors.CameraRecorder;
import fr.inria.tyrex.senslogs.model.sensors.Sensor;

/**
 * Creation of log files asynchronously in a folder
 */
public class RecorderWriter {


    private ExecutorService executor;
    private Context mContext;

    private List<String> mFileNames;
    private List<String> frFileNames;

    private StringBuilder buffer1 = new StringBuilder();
    private StringBuilder buffer2 = new StringBuilder();

    private Map<WritableObject, FileOutputStream> mSensorsFos;
    private Map<WritableObject, File> mSensorsFiles;

    private Map<WritableObject, FileOutputStream> frSensorsFos;
    private Map<WritableObject, File> frSensorsFiles;

    //private Map<Integer, Map<WritableObject, FileOutputStream>> frIterationSensorsFos;
    //private Map<Integer, Map<WritableObject, File>> frIterationSensorsFiles;

    private File mOutputDirectory;
    private File frOutputDirectory;

    public RecorderWriter(Context context) {
        mContext = context;
        mSensorsFos = new HashMap<>();
        mSensorsFiles = new HashMap<>();
        frSensorsFos = new HashMap<>();
        frSensorsFiles = new HashMap<>();
    }

    public void init(Log log) throws FileNotFoundException {

        executor = Executors.newSingleThreadExecutor();
        mSensorsFos.clear();
        mSensorsFiles.clear();
        frSensorsFos.clear();
        frSensorsFiles.clear();
        buffer1.setLength(0);
        buffer2.setLength(0);

        mOutputDirectory = log.getTemporaryFolder();

        mFileNames = new ArrayList<>();
        frFileNames = new ArrayList<>();

        for (Sensor sensor : log.getSensors()) {
            if (!(sensor instanceof FieldsWritableObject)) continue;
            createFile((FieldsWritableObject) sensor);
        }

    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void updateVideoPath() {
        CameraRecorder cameraRecorder = CameraRecorder.getInstance();
        String fileName = avoidDuplicateFiles(mFileNames,
                cameraRecorder.getStorageFileName(mContext)) +
                "." + cameraRecorder.getFileExtension();
        File file = new File(mOutputDirectory, fileName);
        cameraRecorder.setVideoPath(file.getAbsolutePath());
        mSensorsFiles.put(cameraRecorder, file);
    }

    public Map<WritableObject, FileOutputStream> getFrSensorsFos() {
        return frSensorsFos;
    }

    public Map<WritableObject, File> getFrSensorsFiles() {
        return frSensorsFiles;
    }

    public void clearFrSensorsFos() {
        this.frSensorsFos.clear();
        android.util.Log.d(Application.LOG_TAG, "clearFrSensorsFos");
    }

    public void clearFrSensorsFiles() {
        this.frSensorsFiles.clear();
        android.util.Log.d(Application.LOG_TAG, "clearFrSensorsFiles");
    }

    public void clearFrFileNames() {
        this.frFileNames.clear();
        android.util.Log.d(Application.LOG_TAG, "clearFrFileNames");
    }

    public void setFrOutputDirectory(File frOutputDirectory) {
        this.frOutputDirectory = frOutputDirectory;
        android.util.Log.d(Application.LOG_TAG, "setFrOutputDirectory: " + this.frOutputDirectory.toString());
    }

    public List<String> getFrFileNames() {
        return frFileNames;
    }

    public void setFrFileNames(List<String> frFileNames) {
        this.frFileNames = frFileNames;
    }

    private void createFile(FieldsWritableObject fwo) throws FileNotFoundException {
        createFile(fwo, false);
    }

    public void createFile(FieldsWritableObject fwo, boolean forFlightRecorder) throws FileNotFoundException {

        Resources resources = mContext.getResources();

        File file;
        String fileName;
        if (forFlightRecorder) {
            fileName = avoidDuplicateFiles(frFileNames,
                    fwo.getStorageFileName(mContext)) +
                    "." + fwo.getFileExtension();
            file = new File(frOutputDirectory, fileName);
            frSensorsFiles.put(fwo, file);
            android.util.Log.d(Application.LOG_TAG, "createFile for FlightRecorder: " + fileName);
        } else {
            fileName = avoidDuplicateFiles(mFileNames,
                    fwo.getStorageFileName(mContext)) +
                    "." + fwo.getFileExtension();
            file = new File(mOutputDirectory, fileName);
            mSensorsFiles.put(fwo, file);
            android.util.Log.d(Application.LOG_TAG, "createFile for default recorder: " + fileName);
        }

        FileOutputStream fos = new FileOutputStream(file);
        if (forFlightRecorder) {
            frSensorsFos.put(fwo, fos);
        } else {
            mSensorsFos.put(fwo, fos);
        }

        /*
         * Write files headers
         */
/*        buffer2.append(fileName);
        buffer2.append('\n');
        buffer2.append('\n');
        buffer2.append(fwo.getFieldsDescription(resources));
        buffer2.append('\n');
        buffer2.append(fwo.getWebPage(resources));
        buffer2.append('\n');
        buffer2.append('\n');*/

        boolean first = true;
        //Columns name
        for (String field : fwo.getFields(resources)) {
            if (!first) {
                buffer2.append(';');
            }
            buffer2.append(field);
            first = false;
        }
        buffer2.append('\n');
        byte[] bytes = buffer2.toString().getBytes();

        try {
            fos.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
        buffer2.setLength(0);
    }

    public void asyncWrite(final WritableObject writableObject, final double elapsedTimeSystem,
                           final Double elapsedTimeSensor, final Object[] values) {
        executor.execute(() -> write(writableObject, elapsedTimeSystem, elapsedTimeSensor, values));
    }

    public void write(final WritableObject writableObject, final double elapsedTimeSystem,
                      final Double elapsedTimeSensor, final Object[] values) {
        FileOutputStream fos = mSensorsFos.get(writableObject);
        FileOutputStream frFos = frSensorsFos.get(writableObject);
        try {
            buffer1.append(String.format(Locale.US, "%.3f", elapsedTimeSystem));

            if (elapsedTimeSensor != null) {
                buffer1.append(String.format(Locale.US, " %.3f", elapsedTimeSensor));
            }
            for (Object value : values) {
                buffer1.append(';');
                buffer1.append(value.toString());
            }
            buffer1.append('\n');

            byte[] bytes = buffer1.toString().getBytes();

            fos.write(bytes);
            if (frFos!=null)
                frFos.write(bytes);
            buffer1.setLength(0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public void finish() throws IOException {

        executor.shutdown();

        for (FileOutputStream fos : mSensorsFos.values()) {
            fos.flush();
            fos.close();
        }
    }

    public void frFinish() throws IOException {
        for (FileOutputStream frFos : frSensorsFos.values()) {
            frFos.flush();
            frFos.close();
        }
    }

    private File writeDescriptionFile(Log log) throws IOException {

        File file = new File(mOutputDirectory, mContext.getString(R.string.file_record_properties));

        Wini iniFile = log.generateIniFile(mContext, file, mSensorsFiles.keySet());
        if (iniFile == null) return file;
        iniFile.store();
        return file;
    }


    public long getDataSize() {
        return getDataSize(mOutputDirectory);
    }

    private static long getDataSize(File directory) {
        long length = 0;
        if (!directory.exists()) return 0;
        for (File file : directory.listFiles()) {
            if (file.isFile())
                length += file.length();
            else
                length += getDataSize(file);
        }
        return length;
    }

    private String avoidDuplicateFiles(List<String> fileNames, final String fileName) {

        String newFileName = fileName;

        // For sensors from sensor manager, default storage name will be name.txt and
        // for others name#1.txt, name#2.txt ...
        if (fileNames.contains(fileName) || fileName.charAt(fileName.length() - 1) == '#') {
            int i = 1;
            do {
                newFileName = fileName + i++;
            }
            while (fileNames.contains(newFileName));
        }

        fileNames.add(newFileName);
        return newFileName;
    }


    public void removeFiles() {

        if (mOutputDirectory != null && mOutputDirectory.isDirectory()) {
            for (String child : mOutputDirectory.list()) {
                if (!(new File(mOutputDirectory, child).delete())) {
                    android.util.Log.e(Application.LOG_TAG, "Cannot delete writer tmp file");
                }
            }
            if (!mOutputDirectory.delete()) {
                android.util.Log.e(Application.LOG_TAG, "Cannot delete writer tmp folder");
            }
        }
        mOutputDirectory = null;
    }


    public Pair<File, ZipCreationTask> createZipFile(String fileName, Log log)
            throws IOException {

        File outputFile = new File(mContext.getFilesDir(), fileName + ".zip");

        if (outputFile.exists()) {
            int i = 2;
            while ((outputFile = new File(mContext.getFilesDir(), fileName + "-" + i++ + ".zip")).exists())
                ;
        }

        writeDescriptionFile(log);
        Collection<File> inputFiles = Arrays.asList(mOutputDirectory.listFiles());

//                Collection<File> inputFiles = new ArrayList<>(mSensorsFiles.values());
//        inputFiles.add(writeDescriptionFile(log));

        ZipCreationTask zipTask = new ZipCreationTask();
        ZipCreationTask.Params params = new ZipCreationTask.Params(outputFile, inputFiles);
        zipTask.execute(params);

        return new Pair<>(outputFile, zipTask);
    }


    public void writeReferences(LinkedList<PositionReference> references) throws FileNotFoundException {
        if (references.isEmpty()) return;

        FieldsWritableObject prWritableObject = PositionsReferenceManager.getFieldsWritableObject();
        createFile(prWritableObject);
        for (PositionReference reference : references) {
            write(prWritableObject, reference.elapsedTime, null, reference.toObject());
        }

    }
}

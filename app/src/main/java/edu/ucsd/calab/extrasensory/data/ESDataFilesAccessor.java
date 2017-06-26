package edu.ucsd.calab.extrasensory.data;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import edu.ucsd.calab.extrasensory.ESApplication;

/**
 * Created by Yonatan on 6/21/2017.
 */

public class ESDataFilesAccessor {

    private static final String LOG_TAG = "[ESDataFilesAccessor]";
    private static final String LABEL_DATA_DIRNAME = "extrasensory.labels." + ESSettings.uuid().substring(0,7);
    private static final Context CONTEXT = ESApplication.getTheAppContext();
    private static final String LABEL_NAMES_KEY = "label_names";
    private static final String LABEL_PROBS_KEY = "label_probs";

    private static File getLabelFilesDir() throws IOException {
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            Log.e(LOG_TAG,"!!! External storage is not mounted.");
            throw new IOException("External storage is not mounted.");
        }

        File dataFilesDir = new File(CONTEXT.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),LABEL_DATA_DIRNAME);
//        File dataFilesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),LABEL_DATA_DIRNAME);
        if (!dataFilesDir.exists()) {
            // Create the directory:
            if (!dataFilesDir.mkdirs()) {
                Log.e(LOG_TAG,"!!! Failed creating directory: " + dataFilesDir.getPath());
                throw new IOException("Failed creating directory: " + dataFilesDir.getPath());
            }
        }

        return dataFilesDir;
    }

    /**
     * Write the server predictions for an instance to a textual file that will be available to other apps.
     * @param timestamp The timestamp of the instance
     * @param predictedLabelNames The array of labels in the prediction
     * @param predictedLabelProbs The corresponding probabilities assigned to the labels
     * @return Did we succeed writing the file
     */
    public static boolean writeServerPredictions(ESTimestamp timestamp,String[] predictedLabelNames,double[] predictedLabelProbs) {
        final File instanceLabelsFile;
        try {
            instanceLabelsFile = new File(getLabelFilesDir(),timestamp.toString() + ".json");
        } catch (IOException e) {
            return false;
        }

        // Construct the JSON structure:
        JSONObject jsonObject = new JSONObject();
        JSONArray labelNamesArray = new JSONArray();
        JSONArray labelProbsArray = new JSONArray();
        try {
            if (predictedLabelNames != null && predictedLabelProbs != null) {
                if (predictedLabelNames.length != predictedLabelProbs.length) {
                    Log.e(LOG_TAG, "Got inconsistent length of label names and label probs. Not writing file.");
                    return false;
                }

                for (int i = 0; i < predictedLabelNames.length; i++) {
                    labelNamesArray.put(i, predictedLabelNames[i]);
                    labelProbsArray.put(i,predictedLabelProbs[i]);
                }
            }

            jsonObject.put(LABEL_NAMES_KEY,labelNamesArray);
            jsonObject.put(LABEL_PROBS_KEY,labelProbsArray);
        }
        catch (JSONException e) {
            Log.e(LOG_TAG,"Failed forming a json object for server predictions");
            return false;
        }

        try {
            FileOutputStream fos = new FileOutputStream(instanceLabelsFile);
            fos.write(jsonObject.toString().getBytes());
            fos.close();
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG,"!!! File not found: " + instanceLabelsFile.getPath());
            return false;
        } catch (IOException e) {
            Log.e(LOG_TAG,"!!! Failed to write to json file: " + instanceLabelsFile.getPath());
            return false;
        }

        // If we reached here, everything was fine and we were able to write the JSON file
        Log.d(LOG_TAG,">> Saved labels file: " + instanceLabelsFile.getPath());
        // Add it to the media scanner:
        MediaScannerConnection.scanFile(CONTEXT,
                new String[]{instanceLabelsFile.getAbsolutePath()}, new String[]{"application/json"},
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        Log.d(LOG_TAG,"++ Completed scan for file " + instanceLabelsFile.getPath());
                    }
                });

        return true;
    }
}
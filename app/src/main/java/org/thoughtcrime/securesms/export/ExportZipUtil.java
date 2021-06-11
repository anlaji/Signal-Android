package org.thoughtcrime.securesms.export;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.StorageUtil;
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask;
import org.whispersystems.libsignal.util.Pair;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.thoughtcrime.securesms.R.plurals.ExportZip_adding_n_attachments_to_media_folder;

public class ExportZipUtil extends ProgressDialogAsyncTask<ExportZipUtil.Attachment, Void, Pair<Integer, String>> {

    private static final String TAG = ExportZipUtil.class.getSimpleName ();

    static final int BUFFER = 1024;

    static final int SUCCESS              = 0;
    private static final int FAILURE              = 1;
    private static final int WRITE_ACCESS_FAILURE = 2;

    private final WeakReference<Context> contextReference;

    private final int attachmentCount;
    HashMap<String, Uri> otherFiles;
    private final File zipFile;
    private final ZipOutputStream out;


    public ExportZipUtil (Context context, long threadId, String result) throws IOException {
        this(context, 0, threadId, null, false, result);
    }

    public ExportZipUtil (Context context, int count, long threadId, HashMap<String, Uri> otherFiles, boolean hasViewer, String result) throws IOException {
        super(context,
                context.getResources().getQuantityString(R.plurals.ExportZip_start_to_export, count, count),
                context.getResources().getQuantityString(ExportZip_adding_n_attachments_to_media_folder, count, count));
        this.contextReference      = new WeakReference<>(context);
        this.attachmentCount       = count;
        this.otherFiles = otherFiles;
        this.zipFile = getZipFile(threadId);
        this.out = getZipOutputStream();
        addXMLFile ("/chat.xml", result);
       //TODO: if(hasViewer) zipFolder ("viewer_path");
    }

    private File getZipFile (long threadId) {
        File root = new File(getExternalPathToSaveZip ());
        String timestamp = new SimpleDateFormat("yyyy-MM-dd-HHmmss", Resources.getSystem().getConfiguration().locale).format(new Date ());
        String groupName;
        groupName = Objects.requireNonNull (DatabaseFactory.getThreadDatabase (getContext ()).getRecipientForThreadId (threadId)).getDisplayName (getContext ());
        groupName = groupName.replaceAll("[^a-zA-Z0-9.\\-]", "_");
        String fileName = String.format("/%s-%s_signalChatExport", timestamp, groupName);
        return new File(root.getAbsolutePath () + "/" + fileName + ".zip");
    }

    @SuppressLint("LogTagInlined")
    public ZipOutputStream getZipOutputStream() throws IOException {
        try {

            String zipPath = "";
            if(zipFile != null)
                zipPath = zipFile.getPath ();
            FileOutputStream dest = new FileOutputStream(zipPath+"/");//or name
            return new ZipOutputStream(new BufferedOutputStream(dest));
        } catch (IOException e) {
            throw new IOException("Chat export file had an error.\"");
        }
    }


    @SuppressLint("LogTagInlined")
    public @NonNull
    static String getMediaStoreContentPathForType (@NonNull String contentType) {
        if (contentType.startsWith("video/")) {
            return "/Media/Signal Videos/";
        } else if (contentType.startsWith("audio/")) {
            return "/Media/Signal Audios/";
        } else if (contentType.startsWith("image/")) {
            if (contentType.endsWith ("gif")) return "/Media/Signal GIFs/";
            else if (contentType.endsWith ("webp")) return "/Media/Signal Stickers/";
            else return "/Media/Signal Images/";
        }
        else if (contentType.startsWith("application/")) {
            return "/Media/Signal Documents/";
        } else {
            return "/Media/Signal Other Things/";
        }
    }

    private String createOutputPath(@NonNull String outputUri, @NonNull String fileName)
            throws IOException {
        String[] fileParts = getFileNameParts (fileName);
        String base = fileParts[0];
        String extension = fileParts[1];

        File outputDirectory = new File (outputUri);
        File outputFile = new File (outputDirectory, base + "." + extension);

        int i = 0;
        while (outputFile.exists ()) {
            outputFile = new File (outputDirectory, base + "-" + (++i) + "." + extension);
        }

        if (outputFile.isHidden ()) {
            throw new IOException ("Specified name would not be visible");
        }
        return outputFile.getAbsolutePath ();
    }

    @SuppressLint("LogTagInlined")
    private String saveAttachment (Context context, Attachment attachment) throws IOException{
        //TODO --removelog
        Log.i(TAG + "ANGELA Zip saveAttachment", "This" + attachment.fileName  +  " " +  attachment.contentType);
        String      contentType = Objects.requireNonNull(MediaUtil.getCorrectedMimeType(attachment.contentType));
        String      fileName = generateOutputFileName(contentType, attachment.date);

        String           outputUri    = getMediaStoreContentPathForType(contentType);
        String           mediaUri     = createOutputPath( outputUri, fileName);

            try (InputStream inputStream = PartAuthority.getAttachmentStream(context, attachment.uri)) {
                BufferedInputStream origin = new BufferedInputStream (inputStream, BUFFER);
                ZipEntry entry = new ZipEntry (mediaUri);
                entry.setTime (attachment.date);
                out.putNextEntry (entry);
                byte[] buffer = new byte[BUFFER];
                int len;
                while ((len = origin.read (buffer)) > 0) {
                    out.write (buffer, 0, len);
                }
                origin.close ();
            out.closeEntry ();
        } catch (IOException ex) {
            ex.printStackTrace ();
                Log.w(TAG, ex);
        }
        return outputUri;
    }

    public static String generateOutputFileName (@NonNull String contentType, long timestamp) {
        MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton ();
        String extension = mimeTypeMap.getExtensionFromMimeType (contentType);
        @SuppressLint("SimpleDateFormat") SimpleDateFormat dateFormatter = new SimpleDateFormat ("yyyy-MM-dd-HHmmss");
        String base = "signal-" + dateFormatter.format (timestamp);

        if (extension == null) extension = "attach";

        return base + "." + extension;
    }



    private String getExternalPathToSaveZip () {
        File storage = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return storage.getAbsolutePath ();
    }

    @SuppressLint("LogTagInlined")
    public void addXMLFile (String name, String data) {
        try {
            byte[] buffer = new byte[1024];
            ZipEntry zipEntry = new ZipEntry(name);
            out.putNextEntry(zipEntry);

            ByteArrayInputStream bais = new ByteArrayInputStream(data.getBytes());
            BufferedInputStream origin = new BufferedInputStream (bais, BUFFER);
            try {
                int len;
                while ((len = origin.read (buffer)) > 0) {
                    out.write (buffer, 0, len);
                }
                origin.close ();
            } finally {
                bais.close ();
            }
            out.closeEntry ();

        } catch (IOException ex) {
            ex.printStackTrace ();
        }
    }
/*
    @SuppressLint("LogTagInlined")
    private void zipFolder (String inputFolderPath) {
        try {
            File srcFile = new File(inputFolderPath);
            File[] files = srcFile.listFiles();
            Log.d(TAG, "Zip directory: " + srcFile.getName());
            for (int i = 0; i < files.length; i++) {
                Log.d("", "Adding file: " + files[i].getName());
                byte[] buffer = new byte[BUFFER];
                FileInputStream fis = new FileInputStream(files[i]);
                out.putNextEntry(new ZipEntry(files[i].getName()));
                int length;
                while ((length = fis.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
                out.closeEntry();
                fis.close();
            }
            out.close();
        } catch (IOException ioe) {
            Log.e("", ioe.getMessage());
        }
    }*/

    public void closeZip () throws IOException {
        out.close();
    }


    private String[] getFileNameParts(String fileName) {
        String[] result = new String[2];
        String[] tokens = fileName.split("\\.(?=[^.]+$)");

        result[0] = tokens[0];

        if (tokens.length > 1) result[1] = tokens[1];
        else                   result[1] = "";

        return result;
    }

    @Override
    protected Pair<Integer, String> doInBackground(ExportZipUtil.Attachment... attachments) {
        if (attachments == null) {
            throw new AssertionError("must pass in at least one attachment");
        }

        try {
            Context      context      = contextReference.get();
            String       directory    = null;

            if (!StorageUtil.canWriteToMediaStore()) {
                return new Pair<>(WRITE_ACCESS_FAILURE, null);
            }

            if (context == null) {
                return new Pair<>(FAILURE, null);
            }
            for (ExportZipUtil.Attachment attachment : attachments) {
                if (attachment != null) {
                    directory = saveAttachment(context, attachment);
                    if (directory == null) return new Pair<>(FAILURE, null);
                }
            }

            if (attachments.length > 1) return new Pair<>(SUCCESS, null);
            else                        return new Pair<>(SUCCESS, directory);
        } catch (IOException ioe) {
            Log.w(TAG, ioe);
            return new Pair<>(FAILURE, null);
        } finally{
            try {
                out.close ();
            } catch (IOException e) {
                e.printStackTrace ();
            }
        }
    }
    @Override
    protected void onPostExecute(final Pair<Integer, String> result) {
        super.onPostExecute(result);
        final Context context = contextReference.get();
        if (context == null) return;

        switch (result.first()) {
            case FAILURE:
                Toast.makeText(context,
                        context.getResources().getQuantityText(R.plurals.ExportZip_error_while_adding_attachments_to_external_storage,
                                attachmentCount),
                        Toast.LENGTH_LONG).show();
                break;
            case SUCCESS:
                String message = !TextUtils.isEmpty(result.second())  ? context.getResources().getString(R.string.SaveAttachmentTask_saved_to, result.second())
                        : context.getResources().getString(R.string.SaveAttachmentTask_saved);
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                break;
            case WRITE_ACCESS_FAILURE:
                Toast.makeText(context, R.string.ExportZip_unable_to_write_to_sd_card_exclamation,
                        Toast.LENGTH_LONG).show();
                break;
        }
        try {
            closeZip ();
        } catch (IOException e) {
            e.printStackTrace ();
        }
    }

    public static class Attachment {
        public Uri uri;
        public String fileName;
        public String contentType;
        public long   date;
        private long size;

        public Attachment(Uri uri, String contentType,
                          long date, @Nullable String fileName, long size)
        {
            if (uri == null || contentType == null || date < 0) {
                throw new AssertionError("uri, content type, and date must all be specified");
            }
            this.uri         = uri;
            this.fileName    = fileName;
            this.contentType = contentType;
            this.date        = date;
            this.size        = size;
        }

        public long length () {
            return size;
        }
    }

    public static void showWarningDialog(Context context, DialogInterface.OnClickListener onAcceptListener, int count) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.ExportZip_save_to_external_storage);
        builder.setIcon(R.drawable.ic_warning);
        builder.setCancelable(true);
        builder.setMessage(context.getResources().getQuantityString(R.plurals.ExportZip_adding_n_media_to_storage_warning,
                count, count));
        builder.setPositiveButton(R.string.yes, onAcceptListener);
        builder.setNegativeButton(R.string.no, null);
        builder.show();
    }
}

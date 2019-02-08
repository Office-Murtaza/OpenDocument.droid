package at.tomtasche.reader.background;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.OnProgressListener;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.UUID;

import at.tomtasche.reader.background.Document.Page;

public class UpLoader implements FileLoader, OnProgressListener<UploadTask.TaskSnapshot> {

    private Context context;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private Handler mainHandler;

    private FileLoaderListener listener;

    private boolean initialized;
    private boolean loading;

    private int progress;

    private StorageReference storage;
    private FirebaseAuth auth;

    public UpLoader(Context context) {
        this.context = context;
    }

    @Override
    public void initialize(FileLoaderListener listener) {
        this.listener = listener;

        storage = FirebaseStorage.getInstance().getReference();
        auth = FirebaseAuth.getInstance();

        mainHandler = new Handler();

        backgroundThread = new HandlerThread(DocumentLoader.class.getSimpleName());
        backgroundThread.start();

        backgroundHandler = new Handler(backgroundThread.getLooper());

        initialized = true;
    }

    @Override
    public double getProgress() {
        return progress;
    }

    @Override
    public boolean isLoading() {
        return loading;
    }

    @Override
    public void loadAsync(Uri uri, String password, boolean limit, boolean translatable) {
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                loadSync(uri, password, limit, translatable);
            }
        });
    }

    @Override
    public void loadSync(Uri uri, String password, boolean limit, boolean translatable) {
        if (!initialized) {
            throw new RuntimeException("not initialized");
        }

        loading = true;
        progress = 0;

        Task<AuthResult> authenticationTask = null;
        String currentUserId = null;
        if (auth.getCurrentUser() != null) {
            currentUserId = auth.getCurrentUser().getUid();
        } else {
            authenticationTask = auth.signInAnonymously();
        }

        String filename = null;
        try {
            // https://stackoverflow.com/a/38304115/198996
            Cursor fileCursor = context.getContentResolver().query(uri, null, null, null, null);
            if (fileCursor != null && fileCursor.moveToFirst()) {
                int nameIndex = fileCursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME);
                filename = fileCursor.getString(nameIndex);
                fileCursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();

            // "URI does not contain a valid access token." or
            // "Couldn't read row 0, col -1 from CursorWindow. Make sure the Cursor is initialized correctly before accessing data from it."
        }

        if (filename == null) {
            filename = uri.getLastPathSegment();
        }

        try {
            RecentDocumentsUtil.addRecentDocument(context, filename, uri);
        } catch (IOException e) {
            e.printStackTrace();
        }

        String type = context.getContentResolver().getType(uri);
        if (type == null && filename != null) {
            try {
                type = URLConnection.guessContentTypeFromName(filename);
            } catch (Exception e) {
                // Samsung S7 Edge crashes with java.lang.StringIndexOutOfBoundsException
                e.printStackTrace();
            }
        }

        if (type == null) {
            try {
                InputStream stream = context.getContentResolver().openInputStream(uri);
                try {
                    type = URLConnection.guessContentTypeFromStream(stream);
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (type != null
                && (type.equals("text/html") || type.equals("text/plain")
                || type.equals("image/png") || type.equals("image/jpeg"))) {
            try {
                Document document = new Document(null);
                document.addPage(new Page("Document", new URI(uri.toString()),
                        0));

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) {
                            listener.onSuccess(document);
                        }
                    }
                });

                loading = false;
                return;
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }

        InputStream stream = null;
        try {
            if (authenticationTask != null) {
                Tasks.await(authenticationTask);

                currentUserId = authenticationTask.getResult().getUser().getUid();
            }

            stream = context.getContentResolver().openInputStream(uri);

            String fileExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
            StorageReference reference = storage.child("uploads/" + currentUserId + "/" + UUID.randomUUID() + "." + fileExtension);

            UploadTask uploadTask = reference.putStream(stream);
            uploadTask.addOnProgressListener(this);
            Tasks.await(uploadTask);

            if (uploadTask.isSuccessful()) {
                Task<Uri> urlTask = reference.getDownloadUrl();
                Tasks.await(urlTask);

                String downloadUrl = urlTask.getResult().toString();

                URI viewerUri = URI
                        .create("https://docs.google.com/viewer?embedded=true&url="
                                + URLEncoder.encode(downloadUrl, "UTF-8"));

                Document document = new Document(null);
                document.addPage(new Page("Document", viewerUri, 0));

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) {
                            listener.onSuccess(document);
                        }
                    }
                });
            } else {
                throw new RuntimeException("server couldn't handle request");
            }
        } catch (Throwable e) {
            e.printStackTrace();

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.onError(e);
                    }
                }
            });
        } finally {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException e) {
            }
        }

        loading = false;
    }

    @Override
    public void onProgress(UploadTask.TaskSnapshot taskSnapshot) {
        try {
            progress = (int) (taskSnapshot.getTotalByteCount() / taskSnapshot.getTotalByteCount());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                initialized = false;
                listener = null;
                context = null;
                auth = null;
                storage = null;

                backgroundThread.quit();
                backgroundThread = null;

                backgroundHandler = null;
            }
        });
    }
}
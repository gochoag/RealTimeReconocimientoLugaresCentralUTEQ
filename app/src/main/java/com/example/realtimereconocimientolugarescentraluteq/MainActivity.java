package com.example.realtimereconocimientolugarescentraluteq;


import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.ThumbnailUtils;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.example.realtimereconocimientolugarescentraluteq.ml.ModelUnquant;


import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;

import camerax.CameraConnectionFragment;
import camerax.ImageUtils;


public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener, ImageReader.OnImageAvailableListener {
    ArrayList<String> permisosNoAprobados;
    TextView txtResults;
    ImageView mImageView;
    Bitmap mSelectedImage;
    Button btnCamara, btnGaleria, btnReconocimiento;

    String[] labels;
    TextToSpeech textToSpeech;
    private static final int IMAGE_SIZE = 224;
    public static int REQUEST_CAMERA = 111;
    public static int REQUEST_GALLERY = 222;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        txtResults = findViewById(R.id.txtresults);
        //mImageView = findViewById(R.id.image_view);
        btnCamara=findViewById(R.id.btCamera);
        btnGaleria=findViewById(R.id.btGallery);
        //btnReconocimiento=findViewById(R.id.btnReconocer);
        textToSpeech = new TextToSpeech(this, this);
        labels=new String[1001];
        int cnt=0;
        try {
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(getAssets().open("labels.txt")));
            String linea =  bufferedReader.readLine();
            while(linea!=null){
                labels[cnt]=linea;
                cnt++;
                linea =  bufferedReader.readLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }



        ArrayList<String> permisos_requeridos = new ArrayList<String>();
        permisos_requeridos.add(Manifest.permission.CAMERA);
        permisos_requeridos.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        permisos_requeridos.add(Manifest.permission.MANAGE_EXTERNAL_STORAGE);

        permisosNoAprobados = getPermisosNoAprobados(permisos_requeridos);
        requestPermissions(permisosNoAprobados.toArray(new String[permisosNoAprobados.size()]), 100);



    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = textToSpeech.setLanguage(new Locale("es", "ES"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("error", "This Language is not supported");
            } else {
                texttoSpeak();
            }
        } else {
            Log.e("error", "Failed to Initialize");
        }
    }
    @Override
    public void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }


    public void abrirGaleria (View view){
        Intent i = new Intent(Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(i, REQUEST_GALLERY);
    }
    public void abrirCamera (View view){
        //Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
       // startActivityForResult(intent, REQUEST_CAMERA);
        this.setFragment();
    }

    //Metodos para cameraX
    int previewHeight = 0,previewWidth = 0;
    int sensorOrientation;
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    protected void setFragment() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String cameraId = null;
        try {
            cameraId = manager.getCameraIdList()[0];
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        CameraConnectionFragment fragment;
        CameraConnectionFragment camera2Fragment =
                CameraConnectionFragment.newInstance(
                        new CameraConnectionFragment.ConnectionCallback() {
                            @Override
                            public void onPreviewSizeChosen(final Size size, final int rotation) {
                                previewHeight = size.getHeight();    previewWidth = size.getWidth();
                                sensorOrientation = rotation - getScreenOrientation();
                            }
                        },
                        this,   R.layout.camerafragment,     new Size(640, 480));

        camera2Fragment.setCamera(cameraId);
        fragment = camera2Fragment;
        getFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
    }
    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }
    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;
    private Runnable postInferenceCallback;
    private Runnable imageConverter;
    private Bitmap rgbFrameBitmap;
    @Override
    public void onImageAvailable(ImageReader imageReader) {
        if (previewWidth == 0 || previewHeight == 0)           return;
        if (rgbBytes == null)    rgbBytes = new int[previewWidth * previewHeight];
        try {
            final Image image = imageReader.acquireLatestImage();
            if (image == null)    return;
            if (isProcessingFrame) {           image.close();            return;         }
            isProcessingFrame = true;
            final Image.Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();
            imageConverter =  new Runnable() {
                @Override
                public void run() {
                    ImageUtils.convertYUV420ToARGB8888( yuvBytes[0], yuvBytes[1], yuvBytes[2], previewWidth,  previewHeight,
                            yRowStride,uvRowStride, uvPixelStride,rgbBytes);
                }
            };
            postInferenceCallback =      new Runnable() {
                @Override
                public void run() {  image.close(); isProcessingFrame = false;  }
            };
            //LLAMO A METODO
            procesarImagen();

        } catch (final Exception e) {    }

    }
    protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }
//------------------------
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && null != data) {
            try {
                if (requestCode == REQUEST_CAMERA)
                    mSelectedImage = (Bitmap) data.getExtras().get("data");

                else
                    mSelectedImage = MediaStore.Images.Media.getBitmap(getContentResolver(), data.getData());

                if(mSelectedImage!=null){
                    mSelectedImage = displayImageAndClassify(mSelectedImage);
                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Bitmap displayImageAndClassify(Bitmap image) {
        int dimension = Math.min(image.getWidth(), image.getHeight());
        image = ThumbnailUtils.extractThumbnail(image, dimension, dimension);
        mImageView.setImageBitmap(image);

        image = Bitmap.createScaledBitmap(image, IMAGE_SIZE, IMAGE_SIZE, false);
        return image;
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        for(int i=0; i<permissions.length; i++){
            if(permissions[i].equals(Manifest.permission.CAMERA)){
                btnCamara.setEnabled(grantResults[i] == PackageManager.PERMISSION_GRANTED);
            }
        }
    }

    public ArrayList<String> getPermisosNoAprobados(ArrayList<String> listaPermisos) {
        ArrayList<String> list = new ArrayList<String>();
        boolean habilitado;

        if (Build.VERSION.SDK_INT >= 23) {
            for (String permiso : listaPermisos) {
                if (checkSelfPermission(permiso) != PackageManager.PERMISSION_GRANTED) {
                    list.add(permiso);
                    habilitado = false;
                } else {
                    habilitado = true;
                }

                if (permiso.equals(Manifest.permission.CAMERA)) {
                    btnCamara.setEnabled(habilitado);
                }
            }
        }

        return list;
    }
//Metodo de proceso de imagen
    private class UpdateTextTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            try {

                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            texttoSpeak();
        }
    }
    private void procesarImagen() {
        imageConverter.run();

        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
        try {
            Bitmap bitrescatado=Bitmap.createScaledBitmap(rgbFrameBitmap,224,224,true);
            ByteBuffer byteBuffer = getByteBufferFromImage(bitrescatado);
            ModelUnquant model = ModelUnquant.newInstance(MainActivity.this);

            // Creates inputs for reference.
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, IMAGE_SIZE, IMAGE_SIZE, 3}, DataType.FLOAT32);
            inputFeature0.loadBuffer(byteBuffer);

            // Runs model inference and gets result.
            ModelUnquant.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    txtResults.setText(labels[getMax(outputFeature0.getFloatArray())] + " ");
                    new UpdateTextTask().execute();
                    //texttoSpeak();
                }
            });
            // Releases model resources if no longer used.
            model.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        postInferenceCallback.run();
    }

    private void texttoSpeak() {
        String text = txtResults.getText().toString();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
        else {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }
    private ByteBuffer getByteBufferFromImage(Bitmap image) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * IMAGE_SIZE * IMAGE_SIZE * 3);
        byteBuffer.order(ByteOrder.nativeOrder());
        int[] intValues = new int[IMAGE_SIZE * IMAGE_SIZE];
        image.getPixels(intValues, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
        int pixel = 0;
        for (int i = 0; i < IMAGE_SIZE; i++) {
            for (int j = 0; j < IMAGE_SIZE; j++) {
                int val = intValues[pixel++];
                byteBuffer.putFloat(((val >> 16) & 0xFF) * (1.f / 255.f));
                byteBuffer.putFloat(((val >> 8) & 0xFF) * (1.f / 255.f));
                byteBuffer.putFloat((val & 0xFF) * (1.f / 255.f));
            }
        }
        return byteBuffer;
    }

    int getMax(float[] arr)
    {
        int max=0;
        for (int i=0;i<arr.length;i++ ){
            if(arr[i]>arr[max]) max=i;

        }
        return max;
    }


}
package com.locutortts.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int PICK_FILE = 100;
    private static final int SAVE_AUDIO = 101;

    private EditText textBox, nameBox;
    private Spinner voiceBox;
    private SeekBar speedBar, pitchBar;
    private TextView status;
    private ProgressBar progress;
    private Button generateBtn, playBtn, saveBtn, shareBtn;

    private TextToSpeech tts;
    private final List<Voice> voices = new ArrayList<>();
    private final List<String> voiceLabels = new ArrayList<>();
    private List<String> chunks = Collections.emptyList();
    private final List<File> parts = new ArrayList<>();
    private int partIndex;
    private String sessionId = "";
    private Uri savedAudio;
    private File pendingAudioFile;
    private String pendingAudioName = "locucion.wav";
    private MediaPlayer player;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        PDFBoxResourceLoader.init(getApplicationContext());
        buildUi();
        tts = new TextToSpeech(this, this);
    }

    private void buildUi() {
        int p = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p,p,p,p);
        root.setBackgroundColor(Color.rgb(248,250,252));
        scroll.addView(root);

        root.addView(label("🎙️ Locutor TTS", 28));
        TextView help = label("Genera el audio, escúchalo primero y guárdalo solamente cuando te guste.", 15);
        help.setTextColor(Color.DKGRAY);
        root.addView(help);

        LinearLayout row = new LinearLayout(this);
        Button open = new Button(this); open.setText("📄 Abrir archivo"); open.setOnClickListener(v -> pickFile());
        Button clear = new Button(this); clear.setText("Limpiar"); clear.setOnClickListener(v -> textBox.setText(""));
        row.addView(open, new LinearLayout.LayoutParams(0,-2,1));
        row.addView(clear, new LinearLayout.LayoutParams(0,-2,1));
        root.addView(row);

        textBox = new EditText(this);
        textBox.setHint("Escribe o pega aquí tu guion...");
        textBox.setGravity(Gravity.TOP);
        textBox.setMinLines(12);
        textBox.setBackgroundColor(Color.WHITE);
        textBox.setPadding(dp(10),dp(10),dp(10),dp(10));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(300));
        tp.setMargins(0,dp(10),0,dp(10));
        root.addView(textBox,tp);

        root.addView(label("Voz",16));
        voiceBox = new Spinner(this); root.addView(voiceBox);
        root.addView(label("Velocidad",16));
        speedBar = new SeekBar(this); speedBar.setMax(150); speedBar.setProgress(50); root.addView(speedBar);
        root.addView(label("Tono",16));
        pitchBar = new SeekBar(this); pitchBar.setMax(150); pitchBar.setProgress(50); root.addView(pitchBar);

        root.addView(label("Nombre del audio",16));
        nameBox = new EditText(this); nameBox.setText("locucion"); nameBox.setSingleLine(); root.addView(nameBox);

        generateBtn = new Button(this); generateBtn.setText("🎧 GENERAR AUDIO"); generateBtn.setOnClickListener(v -> generate()); root.addView(generateBtn);
        saveBtn = new Button(this); saveBtn.setText("💾 GUARDAR AUDIO"); saveBtn.setEnabled(false); saveBtn.setOnClickListener(v -> saveAudio()); root.addView(saveBtn);

        LinearLayout actions = new LinearLayout(this);
        playBtn = new Button(this); playBtn.setText("▶ Escuchar"); playBtn.setEnabled(false); playBtn.setOnClickListener(v -> play());
        shareBtn = new Button(this); shareBtn.setText("↗ Compartir"); shareBtn.setEnabled(false); shareBtn.setOnClickListener(v -> share());
        actions.addView(playBtn,new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(shareBtn,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(actions);

        progress = new ProgressBar(this); progress.setVisibility(View.GONE); root.addView(progress);
        status = label("Inicializando voz...",14); status.setTextColor(Color.DKGRAY); root.addView(status);
        setContentView(scroll);
    }

    private TextView label(String s, int size) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(Color.rgb(31,41,55)); v.setPadding(0,dp(8),0,dp(6)); return v;
    }

    @Override public void onInit(int result) {
        if (result != TextToSpeech.SUCCESS) { status.setText("No se pudo iniciar el motor TTS."); return; }
        tts.setLanguage(new Locale("es","MX"));
        voices.clear();
        if (tts.getVoices() != null) for (Voice v: tts.getVoices()) if ("es".equals(v.getLocale().getLanguage())) voices.add(v);
        voices.sort(Comparator.comparing(v -> v.getLocale().toLanguageTag()+v.getName()));

        voiceLabels.clear();
        voiceLabels.add("Voz predeterminada · Español México");
        Map<String,Integer> regionCounts = new HashMap<>();
        for (Voice v: voices) {
            String region = voiceRegionName(v.getLocale());
            int n = regionCounts.getOrDefault(region, 0) + 1;
            regionCounts.put(region, n);
            String mode = v.isNetworkConnectionRequired() ? "online" : "sin internet";
            voiceLabels.add("Voz " + region + " " + n + " · " + mode);
        }
        voiceBox.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, voiceLabels));
        voiceBox.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < voiceLabels.size()) status.setText("Voz seleccionada: " + voiceLabels.get(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) {
                if (!id.startsWith(sessionId)) return;
                partIndex++;
                if (partIndex < chunks.size()) runOnUiThread(() -> synthesizePart());
                else new Thread(() -> finishAudio()).start();
            }
            @Override public void onError(String id) { fail("Falló la síntesis de voz."); }
            @Override public void onError(String id, int code) { fail("Error TTS: "+code); }
        });
    }

    private String voiceRegionName(Locale locale) {
        String country = locale == null ? "" : locale.getCountry();
        switch (country) {
            case "MX": return "México";
            case "ES": return "España";
            case "US": return "Latina";
            case "AR": return "Argentina";
            case "CO": return "Colombia";
            case "CL": return "Chile";
            case "PE": return "Perú";
            case "VE": return "Venezuela";
            case "UY": return "Uruguay";
            case "EC": return "Ecuador";
            case "GT": return "Guatemala";
            case "CR": return "Costa Rica";
            case "DO": return "Rep. Dominicana";
            case "PR": return "Puerto Rico";
            case "BO": return "Bolivia";
            case "PY": return "Paraguay";
            case "HN": return "Honduras";
            case "SV": return "El Salvador";
            case "NI": return "Nicaragua";
            case "PA": return "Panamá";
            case "CU": return "Cuba";
            default:
                if (locale != null && !country.isEmpty()) {
                    String display = locale.getDisplayCountry(new Locale("es"));
                    if (display != null && !display.trim().isEmpty()) return display;
                }
                return "Español";
        }
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document"});
        startActivityForResult(i,PICK_FILE);
    }

    private void askWhereToSave() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_TITLE, pendingAudioName);
        startActivityForResult(i, SAVE_AUDIO);
    }

    @Override @SuppressWarnings("deprecation") protected void onActivityResult(int req,int res,Intent data) {
        super.onActivityResult(req,res,data);
        if (req == SAVE_AUDIO) {
            if (res != RESULT_OK || data == null || data.getData() == null) {
                progress.setVisibility(View.GONE);
                generateBtn.setEnabled(true);
                playBtn.setEnabled(pendingAudioFile != null && pendingAudioFile.exists());
                saveBtn.setEnabled(pendingAudioFile != null && pendingAudioFile.exists());
                status.setText("No se guardó, pero el audio sigue disponible para escucharlo.");
                return;
            }
            Uri destination = data.getData();
            progress.setVisibility(View.VISIBLE); status.setText("Guardando audio...");
            new Thread(() -> {
                try {
                    copyToUri(pendingAudioFile, destination);
                    savedAudio = destination;
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE); generateBtn.setEnabled(true);
                        playBtn.setEnabled(true); saveBtn.setEnabled(false); shareBtn.setEnabled(true);
                        status.setText("✅ Audio guardado correctamente.");
                    });
                } catch (Exception e) { fail("No se pudo guardar el audio: " + e.getMessage()); }
            }).start();
            return;
        }

        if (req!=PICK_FILE || res!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData(); progress.setVisibility(View.VISIBLE); status.setText("Leyendo archivo...");
        new Thread(() -> {
            try {
                String name=fileName(uri); String mime=getContentResolver().getType(uri);
                String text=DocumentReader.read(getContentResolver(),uri,name,mime);
                runOnUiThread(() -> { progress.setVisibility(View.GONE); textBox.setText(text); status.setText(text.isEmpty()?"No pude extraer texto; un PDF escaneado necesita OCR.":"Archivo cargado: "+countWords(text)+" palabras."); });
            } catch(Exception e) { runOnUiThread(() -> { progress.setVisibility(View.GONE); status.setText("Error al leer archivo: "+e.getMessage()); }); }
        }).start();
    }

    private String fileName(Uri uri) {
        try(Cursor c=getContentResolver().query(uri,null,null,null,null)) { if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(i>=0)return c.getString(i);} }
        return "archivo";
    }

    private void generate() {
        String text=DocumentReader.clean(textBox.getText().toString());
        if(text.isEmpty()){toast("Escribe texto o abre un archivo.");return;}
        int pos=voiceBox.getSelectedItemPosition(); if(pos>0&&pos-1<voices.size()) tts.setVoice(voices.get(pos-1)); else tts.setLanguage(new Locale("es","MX"));
        tts.setSpeechRate(0.5f+speedBar.getProgress()/100f); tts.setPitch(0.5f+pitchBar.getProgress()/100f);
        chunks=TextChunks.split(text, Math.min(3400,TextToSpeech.getMaxSpeechInputLength()-200));
        parts.clear(); partIndex=0; sessionId=UUID.randomUUID().toString(); savedAudio=null; pendingAudioFile=null;
        File dir=new File(getCacheDir(),"tts_parts"); dir.mkdirs(); File[] old=dir.listFiles(); if(old!=null)for(File f:old)f.delete();
        for(int i=0;i<chunks.size();i++) parts.add(new File(dir,String.format(Locale.US,"part_%03d.wav",i)));
        generateBtn.setEnabled(false); playBtn.setEnabled(false); saveBtn.setEnabled(false); shareBtn.setEnabled(false); progress.setVisibility(View.VISIBLE); synthesizePart();
    }

    private void synthesizePart() {
        status.setText("Generando parte "+(partIndex+1)+" de "+chunks.size()+"...");
        int r=tts.synthesizeToFile(chunks.get(partIndex),new Bundle(),parts.get(partIndex),sessionId+"_"+partIndex);
        if(r!=TextToSpeech.SUCCESS) fail("No se pudo iniciar la síntesis.");
    }

    private void finishAudio() {
        try {
            File merged=new File(getCacheDir(),"locutor_final.wav"); WavMerger.merge(parts,merged);
            String base=nameBox.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]+","_"); if(base.isEmpty())base="locucion";
            pendingAudioFile = merged;
            pendingAudioName = base + ".wav";
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE); generateBtn.setEnabled(true);
                playBtn.setEnabled(true); saveBtn.setEnabled(true); shareBtn.setEnabled(false);
                status.setText("✅ Audio generado. Escúchalo y, si te gusta, pulsa Guardar audio.");
            });
        } catch(Exception e){ fail("No se pudo preparar el audio: "+e.getMessage()); }
    }

    private void saveAudio() {
        if (pendingAudioFile == null || !pendingAudioFile.exists()) { toast("Primero genera un audio."); return; }
        progress.setVisibility(View.VISIBLE); saveBtn.setEnabled(false); status.setText("Guardando audio...");
        new Thread(() -> {
            try {
                savedAudio = saveDownload(pendingAudioFile, pendingAudioName);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE); saveBtn.setEnabled(false); shareBtn.setEnabled(true); playBtn.setEnabled(true);
                    status.setText("✅ Guardado en Descargas/LocutorTTS");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE); saveBtn.setEnabled(true); playBtn.setEnabled(true);
                    status.setText("Tu Android necesita que elijas dónde guardar el audio.");
                    askWhereToSave();
                });
            }
        }).start();
    }

    private Uri saveDownload(File source,String name) throws Exception {
        ContentResolver r=getContentResolver(); ContentValues v=new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME,name); v.put(MediaStore.MediaColumns.MIME_TYPE,"audio/wav"); v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/LocutorTTS"); v.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v); if(uri==null)throw new Exception("Android no pudo crear el archivo.");
        try(InputStream in=new FileInputStream(source); OutputStream out=r.openOutputStream(uri)){ if(out==null)throw new Exception("Android no abrió el archivo de destino."); byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);} catch(Exception e){r.delete(uri,null,null);throw e;}
        v.clear();v.put(MediaStore.MediaColumns.IS_PENDING,0);r.update(uri,v,null,null);return uri;
    }

    private void copyToUri(File source, Uri destination) throws Exception {
        ContentResolver r = getContentResolver();
        try (InputStream in = new FileInputStream(source); OutputStream out = r.openOutputStream(destination, "w")) {
            if (out == null) throw new Exception("No se pudo abrir la ubicación seleccionada.");
            byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); out.flush();
        }
    }

    private void play(){
        try {
            if(player!=null){player.release();player=null;}
            if(savedAudio!=null){ player=MediaPlayer.create(this,savedAudio); }
            else if(pendingAudioFile!=null && pendingAudioFile.exists()){
                player=new MediaPlayer(); player.setDataSource(pendingAudioFile.getAbsolutePath()); player.prepare();
            }
            if(player!=null) player.start(); else toast("No pude abrir el audio.");
        } catch(Exception e){ toast("No se pudo reproducir: "+e.getMessage()); }
    }

    private void share(){
        if(savedAudio==null){toast("Guarda el audio antes de compartirlo.");return;}
        Intent i=new Intent(Intent.ACTION_SEND);i.setType("audio/wav");i.putExtra(Intent.EXTRA_STREAM,savedAudio);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Compartir audio"));
    }

    private void fail(String s){runOnUiThread(() -> {progress.setVisibility(View.GONE);generateBtn.setEnabled(true);status.setText(s);});}
    private int countWords(String s){return s.trim().isEmpty()?0:s.trim().split("\\s+").length;}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    @Override protected void onDestroy(){if(player!=null)player.release();if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}

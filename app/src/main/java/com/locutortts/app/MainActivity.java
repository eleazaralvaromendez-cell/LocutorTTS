package com.locutortts.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int PICK_FILE = 100;
    private static final int SAVE_AUDIO = 101;
    private static final String PREFS = "locutor_tts_preferences";
    private static final String PREF_CURRENT_PROJECT = "current_project";

    private EditText textBox, nameBox;
    private Spinner voiceBox;
    private SeekBar speedBar, pitchBar;
    private TextView speedValue, pitchValue, status, projectTitle, autoSaveState, drawerCurrent;
    private ProgressBar progress;
    private Button generateBtn, playBtn, saveBtn, shareBtn;

    private FrameLayout appFrame;
    private LinearLayout drawer;
    private View drawerScrim;
    private boolean drawerOpen = false;
    private Button drawerProjectsButton;
    private ScrollView drawerProjectsScroll;
    private LinearLayout drawerProjectsContainer;
    private boolean drawerProjectsExpanded = false;

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

    private final Handler autoUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable autoUpdateRunnable;
    private boolean hasGeneratedOnce = false;
    private String pendingVoiceName;
    private String pendingVoiceLabel;

    private final Handler projectSaveHandler = new Handler(Looper.getMainLooper());
    private Runnable projectSaveRunnable;
    private final ExecutorService projectExecutor = Executors.newSingleThreadExecutor();
    private String currentProjectName = "Sin título";
    private boolean suppressAutoSave = false;

    private static final class ProjectSnapshot {
        final String projectName;
        final String text;
        final String audioName;
        final String voiceName;
        final String voiceLabel;
        final int speed;
        final int pitch;

        ProjectSnapshot(String projectName, String text, String audioName,
                        String voiceName, String voiceLabel, int speed, int pitch) {
            this.projectName = projectName;
            this.text = text;
            this.audioName = audioName;
            this.voiceName = voiceName;
            this.voiceLabel = voiceLabel;
            this.speed = speed;
            this.pitch = pitch;
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        PDFBoxResourceLoader.init(getApplicationContext());
        buildUi();
        restoreCurrentProject();
        tts = new TextToSpeech(this, this);
    }

    private void buildUi() {
        int p = dp(16);
        appFrame = new FrameLayout(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p,p,p,p);
        root.setBackgroundColor(Color.rgb(248,250,252));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button menu = new Button(this);
        menu.setText("☰");
        menu.setTextSize(22);
        menu.setMinWidth(0);
        menu.setMinimumWidth(0);
        menu.setPadding(0,0,0,0);
        menu.setOnClickListener(v -> openDrawer());
        header.addView(menu, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView appTitle = label("🎙️ Locutor TTS", 26);
        appTitle.setPadding(dp(8),0,0,0);
        header.addView(appTitle, new LinearLayout.LayoutParams(0,-2,1));
        root.addView(header);

        projectTitle = label("Proyecto: Sin título", 15);
        projectTitle.setTextColor(Color.rgb(55,65,81));
        root.addView(projectTitle);
        autoSaveState = label("✓ Guardado automático", 12);
        autoSaveState.setTextColor(Color.rgb(75,85,99));
        autoSaveState.setPadding(0,0,0,dp(6));
        root.addView(autoSaveState);

        TextView help = label("Genera el audio, escúchalo primero y guárdalo solamente cuando te guste. Tus cambios del proyecto se guardan automáticamente.", 15);
        help.setTextColor(Color.DKGRAY);
        root.addView(help);

        LinearLayout row = new LinearLayout(this);
        Button open = new Button(this); open.setText("📄 Abrir archivo"); open.setOnClickListener(v -> pickFile());
        Button clear = new Button(this); clear.setText("Limpiar"); clear.setOnClickListener(v -> confirmClearText());
        row.addView(open, new LinearLayout.LayoutParams(0,-2,1));
        row.addView(clear, new LinearLayout.LayoutParams(0,-2,1));
        root.addView(row);

        textBox = new EditText(this);
        textBox.setHint("Escribe o pega aquí tu guion...");
        textBox.setGravity(Gravity.TOP);
        textBox.setMinLines(12);
        textBox.setBackgroundColor(Color.WHITE);
        textBox.setPadding(dp(10),dp(10),dp(10),dp(10));

        // El EditText ya sabe desplazarse por sí solo. Solo evitamos que el
        // ScrollView de toda la pantalla robe el gesto mientras el dedo está
        // dentro del guion. No forzamos scrollbars ni movement methods.
        textBox.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });

        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(300));
        tp.setMargins(0,dp(10),0,dp(10));
        root.addView(textBox,tp);

        root.addView(label("Voz",16));
        voiceBox = new Spinner(this); root.addView(voiceBox);

        speedValue = label("Velocidad · 1.00x",16);
        root.addView(speedValue);
        speedBar = new SeekBar(this); speedBar.setMax(150); speedBar.setProgress(50); root.addView(speedBar);

        pitchValue = label("Tono · 1.00x",16);
        root.addView(pitchValue);
        pitchBar = new SeekBar(this); pitchBar.setMax(150); pitchBar.setProgress(50); root.addView(pitchBar);

        setupAutoSlider(speedBar, true);
        setupAutoSlider(pitchBar, false);

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

        appFrame.addView(scroll, new FrameLayout.LayoutParams(-1,-1));
        buildDrawer();
        setContentView(appFrame);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { scheduleProjectSave(2000); }
            @Override public void afterTextChanged(Editable s) {}
        };
        textBox.addTextChangedListener(watcher);
        nameBox.addTextChangedListener(watcher);
    }

    private void buildDrawer() {
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x66000000);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        appFrame.addView(drawerScrim, new FrameLayout.LayoutParams(-1,-1));

        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setPadding(dp(18), dp(24), dp(18), dp(18));
        drawer.setBackgroundColor(Color.WHITE);
        drawer.setVisibility(View.GONE);

        TextView title = label("🎙️ Locutor TTS", 24);
        drawer.addView(title);
        drawerCurrent = label("Proyecto: Sin título", 14);
        drawerCurrent.setTextColor(Color.DKGRAY);
        drawer.addView(drawerCurrent);

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(229,231,235));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(0,dp(12),0,dp(12));
        drawer.addView(divider, dividerParams);

        Button newProject = new Button(this);
        newProject.setText("＋ Nuevo proyecto");
        newProject.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        newProject.setOnClickListener(v -> askNewProject());
        drawer.addView(newProject, new LinearLayout.LayoutParams(-1, dp(56)));

        drawerProjectsButton = new Button(this);
        drawerProjectsButton.setText("📚 Proyectos  ▸");
        drawerProjectsButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        drawerProjectsButton.setOnClickListener(v -> toggleDrawerProjects());
        drawer.addView(drawerProjectsButton, new LinearLayout.LayoutParams(-1, dp(56)));

        drawerProjectsScroll = new ScrollView(this);
        drawerProjectsScroll.setVisibility(View.GONE);
        drawerProjectsScroll.setFillViewport(true);
        drawerProjectsContainer = new LinearLayout(this);
        drawerProjectsContainer.setOrientation(LinearLayout.VERTICAL);
        drawerProjectsContainer.setPadding(dp(8), 0, 0, dp(6));
        drawerProjectsScroll.addView(drawerProjectsContainer, new ScrollView.LayoutParams(-1, -2));
        drawer.addView(drawerProjectsScroll, new LinearLayout.LayoutParams(-1, dp(120)));

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int drawerWidth = Math.min(dp(300), (int)(screenWidth * 0.86f));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(drawerWidth, -1, Gravity.START);
        appFrame.addView(drawer, params);
    }

    private void toggleDrawerProjects() {
        drawerProjectsExpanded = !drawerProjectsExpanded;
        if (!drawerProjectsExpanded) {
            drawerProjectsButton.setText("📚 Proyectos  ▸");
            drawerProjectsScroll.setVisibility(View.GONE);
            return;
        }
        drawerProjectsButton.setText("📚 Proyectos  ▾");
        drawerProjectsScroll.setVisibility(View.VISIBLE);
        loadDrawerProjects();
    }

    private void loadDrawerProjects() {
        saveCurrentProjectAsync(false);
        drawerProjectsContainer.removeAllViews();
        TextView loading = label("Cargando proyectos...", 13);
        loading.setTextColor(Color.GRAY);
        drawerProjectsContainer.addView(loading);

        projectExecutor.execute(() -> {
            try {
                List<ProjectStore.Project> projects = ProjectStore.list(this);
                runOnUiThread(() -> {
                    drawerProjectsContainer.removeAllViews();
                    if (projects.isEmpty()) {
                        TextView empty = label("Aún no hay proyectos.", 13);
                        empty.setTextColor(Color.GRAY);
                        drawerProjectsContainer.addView(empty);
                        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) drawerProjectsScroll.getLayoutParams();
                        params.height = dp(58);
                        drawerProjectsScroll.setLayoutParams(params);
                        return;
                    }

                    DateFormat formatter = DateFormat.getDateTimeInstance(
                            DateFormat.SHORT, DateFormat.SHORT, new Locale("es", "MX"));
                    for (ProjectStore.Project project : projects) {
                        LinearLayout projectRow = new LinearLayout(this);
                        projectRow.setOrientation(LinearLayout.HORIZONTAL);
                        projectRow.setGravity(Gravity.CENTER_VERTICAL);

                        Button projectButton = new Button(this);
                        String currentMark = project.projectName.equals(currentProjectName) ? "● " : "";
                        String date = formatter.format(new Date(project.updatedAt));
                        projectButton.setText(currentMark + project.projectName + "\n" + countWords(project.text) + " palabras · " + date);
                        projectButton.setAllCaps(false);
                        projectButton.setTextSize(13);
                        projectButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                        projectButton.setPadding(dp(10), dp(5), dp(4), dp(5));
                        projectButton.setOnClickListener(v -> {
                            openProject(project, true);
                            drawerProjectsExpanded = false;
                            drawerProjectsButton.setText("📚 Proyectos  ▸");
                            drawerProjectsScroll.setVisibility(View.GONE);
                            closeDrawer();
                        });

                        Button more = new Button(this);
                        more.setText("⋮");
                        more.setTextSize(20);
                        more.setMinWidth(0);
                        more.setMinimumWidth(0);
                        more.setPadding(0,0,0,0);
                        more.setOnClickListener(v -> showProjectActions(project));

                        projectRow.addView(projectButton, new LinearLayout.LayoutParams(0, dp(60), 1));
                        projectRow.addView(more, new LinearLayout.LayoutParams(dp(44), dp(52)));
                        drawerProjectsContainer.addView(projectRow, new LinearLayout.LayoutParams(-1, dp(60)));
                    }

                    int visibleItems = Math.min(projects.size(), 4);
                    int wantedHeight = dp(60 * visibleItems + 8);
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) drawerProjectsScroll.getLayoutParams();
                    params.height = wantedHeight;
                    drawerProjectsScroll.setLayoutParams(params);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    drawerProjectsContainer.removeAllViews();
                    TextView error = label("No pude cargar los proyectos.", 13);
                    error.setTextColor(Color.RED);
                    drawerProjectsContainer.addView(error);
                });
            }
        });
    }

    private void showProjectActions(ProjectStore.Project project) {
        new AlertDialog.Builder(this)
                .setTitle(project.projectName)
                .setItems(new String[]{"✏️ Renombrar", "🕘 Historial de versiones", "🗑️ Eliminar"}, (dialog, which) -> {
                    if (which == 0) askRenameProject(project);
                    else if (which == 1) showProjectHistory(project);
                    else confirmDeleteProject(project);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showProjectHistory(ProjectStore.Project project) {
        status.setText("Buscando versiones anteriores...");
        projectExecutor.execute(() -> {
            try {
                List<ProjectHistory.Revision> revisions = ProjectHistory.list(this, project.projectName);
                runOnUiThread(() -> {
                    if (revisions.isEmpty()) {
                        status.setText("Aún no hay versiones anteriores de ‘" + project.projectName + "’.");
                        new AlertDialog.Builder(this)
                                .setTitle("🕘 Historial de versiones")
                                .setMessage("Todavía no hay puntos de recuperación. Se crearán automáticamente mientras trabajas y antes de limpiar el texto.")
                                .setPositiveButton("Aceptar", null)
                                .show();
                        return;
                    }

                    DateFormat formatter = DateFormat.getDateTimeInstance(
                            DateFormat.SHORT, DateFormat.SHORT, new Locale("es", "MX"));
                    String[] items = new String[revisions.size()];
                    for (int i = 0; i < revisions.size(); i++) {
                        ProjectHistory.Revision revision = revisions.get(i);
                        items[i] = formatter.format(new Date(revision.createdAt))
                                + " · " + countWords(revision.text) + " palabras";
                    }

                    status.setText(revisions.size() == 1
                            ? "1 versión disponible."
                            : revisions.size() + " versiones disponibles.");
                    new AlertDialog.Builder(this)
                            .setTitle("🕘 " + project.projectName)
                            .setItems(items, (dialog, which) -> confirmRestoreRevision(project, revisions.get(which)))
                            .setNegativeButton("Cerrar", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("No se pudo abrir el historial: " + e.getMessage()));
            }
        });
    }

    private void confirmRestoreRevision(ProjectStore.Project project, ProjectHistory.Revision revision) {
        DateFormat formatter = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT, new Locale("es", "MX"));
        new AlertDialog.Builder(this)
                .setTitle("Restaurar versión")
                .setMessage("¿Restaurar la versión del " + formatter.format(new Date(revision.createdAt))
                        + "?\n\nEl estado actual se guardará primero, así podrás recuperarlo también.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar", (dialog, which) -> restoreProjectRevision(project, revision))
                .show();
    }

    private void restoreProjectRevision(ProjectStore.Project project, ProjectHistory.Revision revision) {
        boolean wasCurrent = project.projectName.equals(currentProjectName);
        ProjectSnapshot currentSnapshot = wasCurrent ? captureProjectSnapshot() : null;
        if (projectSaveRunnable != null) projectSaveHandler.removeCallbacks(projectSaveRunnable);
        autoSaveState.setText("Restaurando versión...");

        projectExecutor.execute(() -> {
            try {
                if (currentSnapshot != null) {
                    ProjectHistory.captureIfNeeded(this,
                            currentSnapshot.projectName,
                            currentSnapshot.text,
                            currentSnapshot.audioName,
                            currentSnapshot.voiceName,
                            currentSnapshot.voiceLabel,
                            currentSnapshot.speed,
                            currentSnapshot.pitch);
                    ProjectStore.save(this,
                            currentSnapshot.projectName,
                            currentSnapshot.text,
                            currentSnapshot.audioName,
                            currentSnapshot.voiceName,
                            currentSnapshot.voiceLabel,
                            currentSnapshot.speed,
                            currentSnapshot.pitch);
                }

                ProjectStore.Project restored = ProjectHistory.restore(this, project.projectName, revision);
                runOnUiThread(() -> {
                    if (wasCurrent) openProject(restored, false);
                    autoSaveState.setText("✓ Guardado automático");
                    status.setText("✅ Versión anterior restaurada. El estado que tenías antes también quedó en el historial.");
                    if (drawerProjectsExpanded) loadDrawerProjects();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    autoSaveState.setText("No se pudo restaurar");
                    status.setText("No se pudo restaurar la versión: " + e.getMessage());
                });
            }
        });
    }

    private void askRenameProject(ProjectStore.Project project) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(project.projectName);
        input.selectAll();

        new AlertDialog.Builder(this)
                .setTitle("Renombrar proyecto")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Renombrar", (dialog, which) -> renameProject(project, input.getText().toString().trim()))
                .show();
    }

    private void renameProject(ProjectStore.Project project, String newName) {
        if (newName.isEmpty()) {
            toast("Escribe un nombre para el proyecto.");
            return;
        }
        if (newName.equals(project.projectName)) return;

        boolean wasCurrent = project.projectName.equals(currentProjectName);
        if (wasCurrent) saveCurrentProjectAsync(false);
        projectExecutor.execute(() -> {
            try {
                ProjectStore.Project fresh = ProjectStore.load(this, project.projectName);
                ProjectStore.Project renamed = ProjectStore.rename(this, fresh, newName);
                try {
                    ProjectHistory.renameHistory(this, project.projectName, renamed.projectName);
                } catch (Exception ignored) {
                    // El cambio de nombre del proyecto no debe revertirse si falla mover un historial antiguo.
                }
                runOnUiThread(() -> {
                    if (wasCurrent) setCurrentProjectName(renamed.projectName);
                    status.setText("✅ Proyecto renombrado a ‘" + renamed.projectName + "’. ");
                    if (drawerProjectsExpanded) loadDrawerProjects();
                });
            } catch (Exception e) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("No se pudo renombrar")
                        .setMessage(e.getMessage())
                        .setPositiveButton("Aceptar", null)
                        .show());
            }
        });
    }

    private void confirmDeleteProject(ProjectStore.Project project) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar proyecto")
                .setMessage("¿Quieres eliminar ‘" + project.projectName + "’? Esta acción no se puede deshacer.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> deleteProject(project))
                .show();
    }

    private void deleteProject(ProjectStore.Project project) {
        boolean wasCurrent = project.projectName.equals(currentProjectName);
        if (wasCurrent) saveCurrentProjectAsync(false);
        projectExecutor.execute(() -> {
            try {
                ProjectStore.Project fresh = ProjectStore.load(this, project.projectName);
                ProjectStore.delete(fresh);
                try {
                    ProjectHistory.deleteHistory(this, project.projectName);
                } catch (Exception ignored) {
                    // Si queda un historial huérfano, no debe impedir borrar el proyecto solicitado.
                }
                List<ProjectStore.Project> remaining = ProjectStore.list(this);
                runOnUiThread(() -> {
                    if (wasCurrent) {
                        if (!remaining.isEmpty()) openProject(remaining.get(0), false);
                        else resetToEmptyProject();
                    }
                    status.setText("🗑️ Proyecto ‘" + project.projectName + "’ eliminado.");
                    if (drawerProjectsExpanded) loadDrawerProjects();
                });
            } catch (Exception e) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("No se pudo eliminar")
                        .setMessage(e.getMessage())
                        .setPositiveButton("Aceptar", null)
                        .show());
            }
        });
    }

    private void confirmClearText() {
        if (textBox == null || textBox.getText().toString().isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Limpiar texto")
                .setMessage("¿Quieres borrar todo el texto? Antes de limpiarlo guardaré una versión de recuperación por si fue un accidente.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpiar", (dialog, which) -> checkpointAndClearText())
                .show();
    }

    private void checkpointAndClearText() {
        if (projectSaveRunnable != null) projectSaveHandler.removeCallbacks(projectSaveRunnable);
        ProjectSnapshot snapshot = captureProjectSnapshot();
        autoSaveState.setText("Guardando copia de recuperación...");

        projectExecutor.execute(() -> {
            try {
                ProjectHistory.captureIfNeeded(this,
                        snapshot.projectName,
                        snapshot.text,
                        snapshot.audioName,
                        snapshot.voiceName,
                        snapshot.voiceLabel,
                        snapshot.speed,
                        snapshot.pitch);
                ProjectStore.save(this,
                        snapshot.projectName,
                        snapshot.text,
                        snapshot.audioName,
                        snapshot.voiceName,
                        snapshot.voiceLabel,
                        snapshot.speed,
                        snapshot.pitch);
                ProjectHistory.checkpoint(this, snapshot.projectName);

                runOnUiThread(() -> {
                    if (!snapshot.projectName.equals(currentProjectName)) return;
                    suppressAutoSave = true;
                    textBox.setText("");
                    suppressAutoSave = false;
                    scheduleProjectSave(150);
                    status.setText("Texto limpiado. La versión anterior quedó disponible en Historial de versiones.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    autoSaveState.setText("No se pudo guardar la copia");
                    status.setText("No limpié el texto porque no pude crear una copia de recuperación: " + e.getMessage());
                });
            }
        });
    }

    private void resetToEmptyProject() {
        suppressAutoSave = true;
        setCurrentProjectName("Sin título");
        textBox.setText("");
        nameBox.setText("locucion");
        speedBar.setProgress(50);
        pitchBar.setProgress(50);
        if (voiceBox.getAdapter() != null && voiceBox.getAdapter().getCount() > 0) voiceBox.setSelection(0);
        savedAudio = null;
        pendingAudioFile = null;
        pendingAudioName = "locucion.wav";
        hasGeneratedOnce = false;
        sessionId = "";
        playBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        shareBtn.setEnabled(false);
        suppressAutoSave = false;
        autoSaveState.setText("✓ Guardado automático");
        saveCurrentProjectAsync(false);
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        drawerScrim.setAlpha(0f);
        drawerScrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-drawer.getLayoutParams().width);
        drawer.animate().translationX(0f).setDuration(180).start();
        drawerScrim.animate().alpha(1f).setDuration(180).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        int width = drawer.getLayoutParams().width;
        drawer.animate().translationX(-width).setDuration(160).withEndAction(() -> drawer.setVisibility(View.GONE)).start();
        drawerScrim.animate().alpha(0f).setDuration(160).withEndAction(() -> drawerScrim.setVisibility(View.GONE)).start();
    }

    private void restoreCurrentProject() {
        try {
            String preferred = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_CURRENT_PROJECT, "");
            ProjectStore.Project project = null;
            if (!preferred.isEmpty() && ProjectStore.exists(this, preferred)) {
                project = ProjectStore.load(this, preferred);
            } else {
                List<ProjectStore.Project> projects = ProjectStore.list(this);
                if (!projects.isEmpty()) project = projects.get(0);
            }
            if (project != null) {
                openProject(project, false);
            } else {
                setCurrentProjectName("Sin título");
                autoSaveState.setText("✓ Guardado automático");
            }
        } catch (Exception e) {
            setCurrentProjectName("Sin título");
            status.setText("No pude recuperar el último proyecto, pero puedes seguir trabajando.");
        }
    }

    private void setCurrentProjectName(String name) {
        currentProjectName = (name == null || name.trim().isEmpty()) ? "Sin título" : name.trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_CURRENT_PROJECT, currentProjectName).apply();
        if (projectTitle != null) projectTitle.setText("Proyecto: " + currentProjectName);
        if (drawerCurrent != null) drawerCurrent.setText("Proyecto: " + currentProjectName);
    }

    private void askNewProject() {
        saveCurrentProjectAsync(false);
        closeDrawer();

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Nombre del proyecto");
        input.setText("Nuevo proyecto");
        input.selectAll();

        new AlertDialog.Builder(this)
                .setTitle("Nuevo proyecto")
                .setMessage("El proyecto actual ya quedó guardado. Escribe un nombre para empezar uno nuevo.")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Crear", (dialog, which) -> createNewProject(input.getText().toString().trim()))
                .show();
    }

    private void createNewProject(String projectName) {
        if (projectName.isEmpty()) {
            toast("Escribe un nombre para el proyecto.");
            return;
        }
        if (ProjectStore.exists(this, projectName)) {
            new AlertDialog.Builder(this)
                    .setTitle("Ese proyecto ya existe")
                    .setMessage("Elige otro nombre para no reemplazarlo por accidente.")
                    .setPositiveButton("Elegir otro", (d,w) -> askNewProject())
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }

        suppressAutoSave = true;
        setCurrentProjectName(projectName);
        textBox.setText("");
        nameBox.setText("locucion");
        speedBar.setProgress(50);
        pitchBar.setProgress(50);
        if (voiceBox.getAdapter() != null && voiceBox.getAdapter().getCount() > 0) voiceBox.setSelection(0);
        savedAudio = null;
        pendingAudioFile = null;
        pendingAudioName = "locucion.wav";
        hasGeneratedOnce = false;
        sessionId = "";
        playBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        shareBtn.setEnabled(false);
        suppressAutoSave = false;
        autoSaveState.setText("Guardando...");
        saveCurrentProjectAsync(true);
        status.setText("Proyecto nuevo listo. Empieza a escribir tu guion.");
    }

    private ProjectSnapshot captureProjectSnapshot() {
        int voicePosition = voiceBox == null ? 0 : voiceBox.getSelectedItemPosition();
        String voiceLabel = (voicePosition >= 0 && voicePosition < voiceLabels.size())
                ? voiceLabels.get(voicePosition) : "Voz predeterminada · Español México";
        String voiceName = (voicePosition > 0 && voicePosition - 1 < voices.size())
                ? voices.get(voicePosition - 1).getName() : "";
        return new ProjectSnapshot(
                currentProjectName,
                textBox == null ? "" : textBox.getText().toString(),
                nameBox == null ? "locucion" : nameBox.getText().toString().trim(),
                voiceName,
                voiceLabel,
                speedBar == null ? 50 : speedBar.getProgress(),
                pitchBar == null ? 50 : pitchBar.getProgress());
    }

    private void scheduleProjectSave(long delayMs) {
        if (suppressAutoSave || textBox == null || nameBox == null) return;
        if (projectSaveRunnable != null) projectSaveHandler.removeCallbacks(projectSaveRunnable);
        if (autoSaveState != null) autoSaveState.setText("Cambios pendientes...");
        projectSaveRunnable = () -> saveCurrentProjectAsync(false);
        projectSaveHandler.postDelayed(projectSaveRunnable, delayMs);
    }

    private void saveCurrentProjectAsync(boolean announce) {
        if (suppressAutoSave || textBox == null || nameBox == null) return;
        if (projectSaveRunnable != null) projectSaveHandler.removeCallbacks(projectSaveRunnable);
        ProjectSnapshot snapshot = captureProjectSnapshot();
        projectExecutor.execute(() -> {
            try {
                ProjectHistory.captureIfNeeded(this,
                        snapshot.projectName,
                        snapshot.text,
                        snapshot.audioName,
                        snapshot.voiceName,
                        snapshot.voiceLabel,
                        snapshot.speed,
                        snapshot.pitch);
                ProjectStore.save(this, snapshot.projectName, snapshot.text, snapshot.audioName,
                        snapshot.voiceName, snapshot.voiceLabel, snapshot.speed, snapshot.pitch);
                runOnUiThread(() -> {
                    if (snapshot.projectName.equals(currentProjectName)) {
                        autoSaveState.setText("✓ Guardado automático");
                        if (announce) status.setText("✅ Proyecto ‘" + snapshot.projectName + "’ guardado.");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (snapshot.projectName.equals(currentProjectName)) {
                        autoSaveState.setText("No se pudo guardar");
                        status.setText("No se pudo guardar el proyecto: " + e.getMessage());
                    }
                });
            }
        });
    }

    private void showProjects() {
        saveCurrentProjectAsync(false);
        status.setText("Buscando proyectos guardados...");
        projectExecutor.execute(() -> {
            try {
                List<ProjectStore.Project> projects = ProjectStore.list(this);
                runOnUiThread(() -> {
                    if (projects.isEmpty()) {
                        status.setText("Todavía no hay proyectos guardados.");
                        return;
                    }
                    status.setText(projects.size() == 1 ? "1 proyecto guardado." : projects.size() + " proyectos guardados.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("No se pudieron leer los proyectos: " + e.getMessage()));
            }
        });
    }

    private void openProject(ProjectStore.Project project, boolean announce) {
        suppressAutoSave = true;
        if (tts != null) tts.stop();
        if (player != null) {
            player.release();
            player = null;
        }

        setCurrentProjectName(project.projectName);
        textBox.setText(project.text);
        nameBox.setText(project.audioName == null || project.audioName.trim().isEmpty() ? "locucion" : project.audioName);
        speedBar.setProgress(project.speedProgress);
        pitchBar.setProgress(project.pitchProgress);

        savedAudio = null;
        pendingAudioFile = null;
        hasGeneratedOnce = false;
        sessionId = "";
        playBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        shareBtn.setEnabled(false);

        if (voiceLabels.isEmpty()) {
            pendingVoiceName = project.voiceName;
            pendingVoiceLabel = project.voiceLabel;
        } else {
            selectSavedVoice(project.voiceName, project.voiceLabel);
        }
        suppressAutoSave = false;
        autoSaveState.setText("✓ Guardado automático");
        if (announce) status.setText("✅ Proyecto ‘" + project.projectName + "’ abierto. Pulsa Generar audio cuando quieras escucharlo.");
    }

    private void selectSavedVoice(String voiceName, String voiceLabel) {
        int selected = 0;
        if (voiceName != null && !voiceName.isEmpty()) {
            for (int i = 0; i < voices.size(); i++) {
                if (voiceName.equals(voices.get(i).getName())) {
                    selected = i + 1;
                    break;
                }
            }
        }
        if (selected == 0 && voiceLabel != null && !voiceLabel.isEmpty()) {
            int labelIndex = voiceLabels.indexOf(voiceLabel);
            if (labelIndex >= 0) selected = labelIndex;
        }
        if (voiceBox.getAdapter() != null && selected < voiceBox.getAdapter().getCount()) {
            voiceBox.setSelection(selected);
        }
    }

    private void setupAutoSlider(SeekBar bar, boolean speed) {
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
                float value = 0.5f + progressValue / 100f;
                if (speed) speedValue.setText(String.format(Locale.US, "Velocidad · %.2fx", value));
                else pitchValue.setText(String.format(Locale.US, "Tono · %.2fx", value));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                if (autoUpdateRunnable != null) autoUpdateHandler.removeCallbacks(autoUpdateRunnable);
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                scheduleProjectSave(350);
                scheduleAutomaticRegeneration();
            }
        });
    }

    private void scheduleAutomaticRegeneration() {
        if (!hasGeneratedOnce) {
            status.setText("Ajustes listos. Pulsa Generar audio para crear la primera versión.");
            return;
        }
        if (textBox.getText().toString().trim().isEmpty()) return;

        if (autoUpdateRunnable != null) autoUpdateHandler.removeCallbacks(autoUpdateRunnable);
        status.setText("Actualizando audio con los nuevos ajustes...");
        autoUpdateRunnable = () -> generate(true);
        autoUpdateHandler.postDelayed(autoUpdateRunnable, 450);
    }

    private TextView label(String s, int size) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(Color.rgb(31,41,55));
        v.setPadding(0,dp(8),0,dp(6));
        return v;
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
                scheduleProjectSave(400);
                if (!suppressAutoSave && hasGeneratedOnce) scheduleAutomaticRegeneration();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        if (pendingVoiceName != null || pendingVoiceLabel != null) {
            suppressAutoSave = true;
            selectSavedVoice(pendingVoiceName, pendingVoiceLabel);
            pendingVoiceName = null;
            pendingVoiceLabel = null;
            suppressAutoSave = false;
        }

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) {
                if (sessionId.isEmpty() || !id.startsWith(sessionId)) return;
                partIndex++;
                if (partIndex < chunks.size()) runOnUiThread(this::continueSynthesis);
                else new Thread(() -> finishAudio()).start();
            }
            private void continueSynthesis() { synthesizePart(); }
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
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
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
                status.setText("No se guardó el audio, pero el proyecto sí quedó guardado y el audio sigue disponible para escucharlo.");
                return;
            }
            Uri destination = data.getData();
            progress.setVisibility(View.VISIBLE);
            status.setText("Guardando audio...");
            new Thread(() -> {
                try {
                    copyToUri(pendingAudioFile, destination);
                    savedAudio = destination;
                    runOnUiThread(() -> {
                        saveCurrentProjectAsync(false);
                        progress.setVisibility(View.GONE);
                        generateBtn.setEnabled(true);
                        playBtn.setEnabled(true);
                        saveBtn.setEnabled(false);
                        shareBtn.setEnabled(true);
                        status.setText("✅ Audio guardado. El proyecto sigue activo y actualizado.");
                    });
                } catch (Exception e) { fail("No se pudo guardar el audio: " + e.getMessage()); }
            }).start();
            return;
        }

        if (req!=PICK_FILE || res!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        progress.setVisibility(View.VISIBLE);
        status.setText("Leyendo archivo...");
        new Thread(() -> {
            try {
                String name=fileName(uri);
                String mime=getContentResolver().getType(uri);
                String text=DocumentReader.read(getContentResolver(),uri,name,mime);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    textBox.setText(text);
                    status.setText(text.isEmpty()?"No pude extraer texto; un PDF escaneado necesita OCR.":"Archivo cargado: "+countWords(text)+" palabras.");
                });
            } catch(Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("Error al leer archivo: "+e.getMessage());
                });
            }
        }).start();
    }

    private String fileName(Uri uri) {
        try(Cursor c=getContentResolver().query(uri,null,null,null,null)) {
            if(c!=null&&c.moveToFirst()){
                int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(i>=0)return c.getString(i);
            }
        }
        return "archivo";
    }

    private void generate() { generate(false); }

    private void generate(boolean automatic) {
        String text=DocumentReader.clean(textBox.getText().toString());
        if(text.isEmpty()){toast("Escribe texto o abre un archivo.");return;}

        saveCurrentProjectAsync(false);
        if (tts != null) tts.stop();
        if (player != null) { player.release(); player = null; }

        int pos=voiceBox.getSelectedItemPosition();
        if(pos>0&&pos-1<voices.size()) tts.setVoice(voices.get(pos-1));
        else tts.setLanguage(new Locale("es","MX"));

        tts.setSpeechRate(0.5f+speedBar.getProgress()/100f);
        tts.setPitch(0.5f+pitchBar.getProgress()/100f);
        chunks=TextChunks.split(text, Math.min(3400,TextToSpeech.getMaxSpeechInputLength()-200));
        parts.clear();
        partIndex=0;
        sessionId=UUID.randomUUID().toString();
        savedAudio=null;
        pendingAudioFile=null;
        File dir=new File(getCacheDir(),"tts_parts");
        dir.mkdirs();
        File[] old=dir.listFiles();
        if(old!=null)for(File f:old)f.delete();
        for(int i=0;i<chunks.size();i++) parts.add(new File(dir,String.format(Locale.US,"part_%03d.wav",i)));
        generateBtn.setEnabled(false);
        playBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        shareBtn.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText(automatic ? "Actualizando audio automáticamente..." : "Preparando audio...");
        synthesizePart();
    }

    private void synthesizePart() {
        status.setText("Generando parte "+(partIndex+1)+" de "+chunks.size()+"...");
        int r=tts.synthesizeToFile(chunks.get(partIndex),new Bundle(),parts.get(partIndex),sessionId+"_"+partIndex);
        if(r!=TextToSpeech.SUCCESS) fail("No se pudo iniciar la síntesis.");
    }

    private void finishAudio() {
        try {
            File merged=new File(getCacheDir(),"locutor_final.wav");
            WavMerger.merge(parts,merged);
            String base=nameBox.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]+","_");
            if(base.isEmpty())base="locucion";
            pendingAudioFile = merged;
            pendingAudioName = base + ".wav";
            hasGeneratedOnce = true;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                generateBtn.setEnabled(true);
                playBtn.setEnabled(true);
                saveBtn.setEnabled(true);
                shareBtn.setEnabled(false);
                status.setText("✅ Audio actualizado. Puedes escucharlo o guardarlo.");
            });
        } catch(Exception e){ fail("No se pudo preparar el audio: "+e.getMessage()); }
    }

    private void saveAudio() {
        if (pendingAudioFile == null || !pendingAudioFile.exists()) {
            toast("Primero genera un audio.");
            return;
        }
        saveCurrentProjectAsync(false);
        progress.setVisibility(View.VISIBLE);
        saveBtn.setEnabled(false);
        status.setText("Guardando audio...");
        new Thread(() -> {
            try {
                savedAudio = saveDownload(pendingAudioFile, pendingAudioName);
                runOnUiThread(() -> {
                    saveCurrentProjectAsync(false);
                    progress.setVisibility(View.GONE);
                    saveBtn.setEnabled(false);
                    shareBtn.setEnabled(true);
                    playBtn.setEnabled(true);
                    status.setText("✅ Audio guardado en Descargas/LocutorTTS. El proyecto sigue activo y actualizado.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    saveBtn.setEnabled(true);
                    playBtn.setEnabled(true);
                    status.setText("Tu Android necesita que elijas dónde guardar el audio. El proyecto ya quedó guardado.");
                    askWhereToSave();
                });
            }
        }).start();
    }

    private Uri saveDownload(File source,String name) throws Exception {
        ContentResolver r=getContentResolver();
        ContentValues v=new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        v.put(MediaStore.MediaColumns.MIME_TYPE,"audio/wav");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/LocutorTTS");
        v.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
        if(uri==null)throw new Exception("Android no pudo crear el archivo.");
        try(InputStream in=new FileInputStream(source); OutputStream out=r.openOutputStream(uri)){
            if(out==null)throw new Exception("Android no abrió el archivo de destino.");
            byte[] b=new byte[16384];
            int n;
            while((n=in.read(b))>0)out.write(b,0,n);
        } catch(Exception e){
            r.delete(uri,null,null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING,0);
        r.update(uri,v,null,null);
        return uri;
    }

    private void copyToUri(File source, Uri destination) throws Exception {
        ContentResolver r = getContentResolver();
        try (InputStream in = new FileInputStream(source); OutputStream out = r.openOutputStream(destination, "w")) {
            if (out == null) throw new Exception("No se pudo abrir la ubicación seleccionada.");
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.flush();
        }
    }

    private void play(){
        try {
            if(player!=null){player.release();player=null;}
            if(savedAudio!=null){
                player=MediaPlayer.create(this,savedAudio);
            } else if(pendingAudioFile!=null && pendingAudioFile.exists()){
                player=new MediaPlayer();
                player.setDataSource(pendingAudioFile.getAbsolutePath());
                player.prepare();
            }
            if(player!=null) player.start();
            else toast("No pude abrir el audio.");
        } catch(Exception e){ toast("No se pudo reproducir: "+e.getMessage()); }
    }

    private void share(){
        if(savedAudio==null){toast("Guarda el audio antes de compartirlo.");return;}
        Intent i=new Intent(Intent.ACTION_SEND);
        i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_STREAM,savedAudio);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i,"Compartir audio"));
    }

    private void fail(String s){
        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            generateBtn.setEnabled(true);
            status.setText(s);
        });
    }

    private int countWords(String s){return s.trim().isEmpty()?0:s.trim().split("\\s+").length;}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    @Override protected void onPause() {
        if (projectSaveRunnable != null) projectSaveHandler.removeCallbacks(projectSaveRunnable);
        saveCurrentProjectAsync(false);
        super.onPause();
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy(){
        if(autoUpdateRunnable!=null) autoUpdateHandler.removeCallbacks(autoUpdateRunnable);
        if(projectSaveRunnable!=null) projectSaveHandler.removeCallbacks(projectSaveRunnable);
        if(player!=null)player.release();
        if(tts!=null){tts.stop();tts.shutdown();}
        projectExecutor.shutdown();
        super.onDestroy();
    }
}

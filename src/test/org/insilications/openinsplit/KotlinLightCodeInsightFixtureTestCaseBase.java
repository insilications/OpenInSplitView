package org.insilications.openinsplit;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.RunAll;
import com.intellij.testFramework.TempFiles;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;

public abstract class KotlinLightCodeInsightFixtureTestCaseBase extends LightJavaCodeInsightFixtureTestCase
        implements ExpectedPluginModeProvider {

    @NotNull
    @Override
    public Project getProject() {
        return super.getProject();
    }

    @NotNull
    @Override
    public Editor getEditor() {
        return super.getEditor();
    }

    @Override
    public PsiFile getFile() {
        return super.getFile();
    }

    protected final Collection<Path> myFilesToDelete = new HashSet<>();
    private final TempFiles myTempFiles = new TempFiles(myFilesToDelete);

    @Override
    protected void setUp() throws Exception {
        ExpectedPluginModeProviderKt.setUpWithKotlinPlugin(this, super::setUp);
    }

    @Override
    protected void tearDown() throws Exception {
        RunAll.runAll(
                myTempFiles::deleteAll,
                super::tearDown
        );
    }

//    @NotNull
//    public VirtualFile createTempFile(
//            @NonNls @NotNull String ext,
//            byte @Nullable [] bom,
//            @NonNls @NotNull String content,
//            @NotNull Charset charset
//    ) throws IOException {
//        File temp = FileUtil.createTempFile("copy", "." + ext);
//        setContentOnDisk(temp, bom, content, charset);
//
//        myFilesToDelete.add(temp.toPath());
//        final VirtualFile file = getVirtualFile(temp);
//        assert file != null : temp;
//        return file;
//    }
//
//    public static void setContentOnDisk(@NotNull File file, byte @Nullable [] bom, @NotNull String content, @NotNull Charset charset)
//            throws IOException {
//        FileOutputStream stream = new FileOutputStream(file);
//        if (bom != null) {
//            stream.write(bom);
//        }
//        try (OutputStreamWriter writer = new OutputStreamWriter(stream, charset)) {
//            writer.write(content);
//        }
//    }

    protected static VirtualFile getVirtualFile(@NotNull File file) {
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
    }

    // todo inline the method
    protected final boolean isFirPlugin() {
        return getPluginMode() == KotlinPluginMode.K2;
    }

    @Override
    public @NotNull KotlinPluginMode getPluginMode() {
        return KotlinPluginMode.K2;
    }
}

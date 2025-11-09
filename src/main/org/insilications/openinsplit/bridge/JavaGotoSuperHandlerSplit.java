package org.insilications.openinsplit.bridge;

import static org.insilications.openinsplit.codeInsight.navigation.actions.IdeKt.navigateToNavigatable;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.codeInsight.navigation.GotoTargetHandler;
import com.intellij.codeInsight.navigation.PsiTargetNavigator;
import com.intellij.java.JavaBundle;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.psi.*;
import com.intellij.psi.impl.FindSuperElementsHelper;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.ArrayUtil;

import org.insilications.openinsplit.utils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

@SuppressWarnings({"ClassWithoutConstructor", "FinalClass"})
final class JavaGotoSuperHandlerSplit implements GotoSuperActionSplitBridge {
    private static final Logger LOG = Logger.getInstance("org.insilications.openinsplit");
    private static final String SUPER_CLASS_SPLIT = "Go to Super Class or Interface (Split)";
    private static final String SUPER_CLASS_SPLIT_SHORT = "Super Class or Interface (Split)";
    private static final String SUPER_CLASS_SPLIT_DESCRIPTION = "Navigate to the declaration of a class that the current class extends or implements (Split)";
    private static final String SUPER_METHOD_SPLIT = "Go to Super Method (Split)";
    private static final String SUPER_METHOD_SPLIT_SHORT = "Super Method (Split)";
    private static final String SUPER_METHOD_SPLIT_DESCRIPTION =
            "Navigate to the declaration of a method that the current method overrides or implements (Split)";
    private static final String SUPER_METHOD_CHOOSER_TITLE = CodeInsightBundle.message("goto.super.method.chooser.title");
    private static final String GOTO_SUPER_CLASS_CHOOSER_TITLE = JavaBundle.message("goto.super.class.chooser.title");

    @Override
    public @NotNull String getGoToSuperActionSplitLanguage() {
        return "JAVA";
    }

    @Override
    public void invoke(final @NotNull Project project, final @NotNull Editor editor, final @NotNull PsiFile psiFile) {
        utils.debug(JavaGotoSuperHandlerSplit.LOG, () -> "JavaGotoSuperHandlerSplit - invoke");
        new PsiTargetNavigator<>(() -> Arrays.asList(DumbService.getInstance(project)
                                                             .computeWithAlternativeResolveEnabled(() -> JavaGotoSuperHandlerSplit.findSuperElements(
                                                                     psiFile,
                                                                     editor.getCaretModel().getOffset()
                                                             )))).elementsConsumer((elements, navigator) -> {
            if (!elements.isEmpty() && elements.iterator().next() instanceof PsiMethod) {
                boolean showMethodNames = !PsiUtil.allMethodsHaveSameSignature(elements.toArray(PsiMethod.EMPTY_ARRAY));
                navigator.presentationProvider(element -> GotoTargetHandler.computePresentation(element, showMethodNames));
                navigator.title(JavaGotoSuperHandlerSplit.SUPER_METHOD_CHOOSER_TITLE);
            } else {
                navigator.title(JavaGotoSuperHandlerSplit.GOTO_SUPER_CLASS_CHOOSER_TITLE);
            }
        }).navigate(
                editor, null, element -> {
                    navigateToNavigatable(project, (Navigatable) element, null);
                    return true;
                }
        );
    }

    private static PsiElement @NotNull [] findSuperElements(@NotNull PsiFile file, int offset) {
        PsiElement element = JavaGotoSuperHandlerSplit.getElement(file, offset);
        if (element == null) return PsiElement.EMPTY_ARRAY;

        final PsiElement psiElement = PsiTreeUtil.getParentOfType(element, PsiFunctionalExpression.class, PsiMember.class);
        if (psiElement instanceof PsiFunctionalExpression) {
            final PsiMethod interfaceMethod = LambdaUtil.getFunctionalInterfaceMethod(psiElement);
            if (interfaceMethod != null) {
                return ArrayUtil.prepend(interfaceMethod, interfaceMethod.findSuperMethods(false));
            }
        }

        final PsiNameIdentifierOwner parent = PsiTreeUtil.getNonStrictParentOfType(element, PsiMethod.class, PsiClass.class);
        if (parent == null) {
            return PsiElement.EMPTY_ARRAY;
        }

        return FindSuperElementsHelper.findSuperElements(parent);
    }

    @SuppressWarnings("TypeMayBeWeakened")
    private static @Nullable PsiElement getElement(@NotNull PsiFile file, int offset) {
        return file.findElementAt(offset);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public void update(@NotNull Editor editor, @NotNull PsiFile file, @NotNull Presentation presentation, boolean isFromMainMenu, boolean isFromContextMenu) {
        utils.debug(JavaGotoSuperHandlerSplit.LOG, () -> "JavaGotoSuperHandlerSplit - update");
        final PsiElement element = JavaGotoSuperHandlerSplit.getElement(file, editor.getCaretModel().getOffset());
        final PsiElement containingElement = PsiTreeUtil.getParentOfType(element, PsiFunctionalExpression.class, PsiMember.class);

        boolean useShortName = isFromMainMenu || isFromContextMenu;
        if (containingElement instanceof PsiClass) {
            presentation.setText(useShortName ? JavaGotoSuperHandlerSplit.SUPER_CLASS_SPLIT_SHORT : JavaGotoSuperHandlerSplit.SUPER_CLASS_SPLIT);
            presentation.setDescription(JavaGotoSuperHandlerSplit.SUPER_CLASS_SPLIT_DESCRIPTION);
        } else {
            presentation.setText(useShortName ? JavaGotoSuperHandlerSplit.SUPER_METHOD_SPLIT_SHORT : JavaGotoSuperHandlerSplit.SUPER_METHOD_SPLIT);
            presentation.setDescription(JavaGotoSuperHandlerSplit.SUPER_METHOD_SPLIT_DESCRIPTION);
        }
    }
}

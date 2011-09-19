/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInsight.editorActions;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.ide.ClipboardSynchronizer;
import com.intellij.ide.PasteProvider;
import com.intellij.lang.LanguageFormatting;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.editor.actionSystem.EditorTextInsertHandler;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.Indent;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.Producer;
import com.intellij.util.containers.HashMap;
import com.intellij.util.text.CharArrayUtil;
import org.jetbrains.annotations.NonNls;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

public class PasteHandler extends EditorActionHandler implements EditorTextInsertHandler {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.editorActions.PasteHandler");

  public static final String TRANSFERABLE_PROVIDER = "PasteTransferableProvider";
  
  private final EditorActionHandler myOriginalHandler;

  private static final ExtensionPointName<PasteProvider> EP_NAME = ExtensionPointName.create("com.intellij.customPasteProvider");

  public PasteHandler(EditorActionHandler originalAction) {
    myOriginalHandler = originalAction;
  }

  public void execute(final Editor editor, final DataContext dataContext, final Producer<Transferable> transferableProvider) {
    if (editor.isViewer()) return;

      if (!FileDocumentManager.getInstance().requestWriting(editor.getDocument(), PlatformDataKeys.PROJECT.getData(dataContext))){
        return;
      }

    DataContext context = ClipboardSynchronizer.useAlternativeSync() ? new DataContext() {
      @Override
      public Object getData(@NonNls String dataId) {
        if (TRANSFERABLE_PROVIDER.equals(dataId)) {
          return transferableProvider;
        }

        return dataContext.getData(dataId);
      }
    } : dataContext;


    final Project project = editor.getProject();
    if (project == null || editor.isColumnMode() || editor.getSelectionModel().hasBlockSelection()) {
      if (myOriginalHandler != null) {
        myOriginalHandler.execute(editor, context);
      }
      return;
    }
    final Document document = editor.getDocument();
    final PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);

    if (file == null) {
      if (myOriginalHandler != null) {
        myOriginalHandler.execute(editor, context);
      }
      return;
    }

    document.startGuardedBlockChecking();
    try {
      for(PasteProvider provider: Extensions.getExtensions(EP_NAME)) {
        if (provider.isPasteEnabled(context)) {
          provider.performPaste(context);
          return;
        }
      }
      doPaste(editor, project, file, document, transferableProvider);
    }
    catch (ReadOnlyFragmentModificationException e) {
      EditorActionManager.getInstance().getReadonlyFragmentModificationHandler(document).handle(e);
    }
    finally {
      document.stopGuardedBlockChecking();
    }
  }

  public void execute(final Editor editor, final DataContext dataContext) {
    execute(editor, dataContext, new Producer<Transferable>() {
      @Override
      public Transferable produce() {
        CopyPasteManager copyPasteManager = CopyPasteManager.getInstance();
        Transferable contents = copyPasteManager.getContents();
        if (contents != null) {
          copyPasteManager.stopKillRings();
        }
        
        return contents;
      }
    });
  }

  private static void doPaste(final Editor editor,
                              final Project project,
                              final PsiFile file,
                              final Document document,
                              final Producer<Transferable> transferableFunction) {
    Transferable content = transferableFunction.produce();
    if (content != null) {
      String text = null;
      try {
        text = (String)content.getTransferData(DataFlavor.stringFlavor);
      }
      catch (Exception e) {
        editor.getComponent().getToolkit().beep();
      }
      if (text == null) return;

      final CodeInsightSettings settings = CodeInsightSettings.getInstance();

      final Map<CopyPastePostProcessor, TextBlockTransferableData> extraData = new HashMap<CopyPastePostProcessor, TextBlockTransferableData>();
      for(CopyPastePostProcessor processor: Extensions.getExtensions(CopyPastePostProcessor.EP_NAME)) {
        TextBlockTransferableData data = processor.extractTransferableData(content);
        if (data != null) {
          extraData.put(processor, data);
        }
      }

      text = TextBlockTransferable.convertLineSeparators(text, "\n", extraData.values());

      final CaretModel caretModel = editor.getCaretModel();
      final SelectionModel selectionModel = editor.getSelectionModel();
      final int col = caretModel.getLogicalPosition().column;
      
      // There is a possible case that we want to perform paste while there is an active selection at the editor and caret is located
      // inside it (e.g. Ctrl+A is pressed while caret is not at the zero column). We want to insert the text at selection start column
      // then, hence, inserted block of text should be indented according to the selection start as well.
      final int blockIndentAnchorColumn;
      final int caretOffset = caretModel.getOffset();
      if (selectionModel.hasSelection() && caretOffset >= selectionModel.getSelectionStart()
          && caretOffset < selectionModel.getSelectionEnd()) 
      {
        blockIndentAnchorColumn = editor.offsetToLogicalPosition(selectionModel.getSelectionStart()).column;
      }
      else {
        blockIndentAnchorColumn = col;
      }
      
      // We assume that EditorModificationUtil.insertStringAtCaret() is smart enough to understand that text that is currently
      // selected at editor (if any) should be removed.
      //
      //if (selectionModel.hasSelection()) {
      //  ApplicationManager.getApplication().runWriteAction(
      //    new Runnable() {
      //      public void run() {
      //        EditorModificationUtil.deleteSelectedText(editor);
      //      }
      //    }
      //  );
      //}

      RawText rawText = RawText.fromTransferable(content);

      String newText = text;
      for(CopyPastePreProcessor preProcessor: Extensions.getExtensions(CopyPastePreProcessor.EP_NAME)) {
        newText = preProcessor.preprocessOnPaste(project, file, editor, newText, rawText);
      }
      int indentOptions = text.equals(newText) ? settings.REFORMAT_ON_PASTE : CodeInsightSettings.REFORMAT_BLOCK;
      text = newText;

      if (LanguageFormatting.INSTANCE.forContext(file) == null && indentOptions != CodeInsightSettings.NO_REFORMAT) {
        indentOptions = CodeInsightSettings.INDENT_BLOCK;
      }

      int length = text.length();
      final String text1 = text;

      ApplicationManager.getApplication().runWriteAction(
        new Runnable() {
          public void run() {
            EditorModificationUtil.insertStringAtCaret(editor, text1, false, true);
          }
        }
      );

      int offset = caretModel.getOffset() - length;
      if (offset < 0) {
        length += offset;
        offset = 0;
      }
      final RangeMarker bounds = document.createRangeMarker(offset, offset + length);

      caretModel.moveToOffset(bounds.getEndOffset());
      editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
      selectionModel.removeSelection();

      final Ref<Boolean> indented = new Ref<Boolean>(Boolean.FALSE);
      for(Map.Entry<CopyPastePostProcessor, TextBlockTransferableData> e: extraData.entrySet()) {
        //noinspection unchecked
        e.getKey().processTransferableData(project, editor, bounds, col, indented, e.getValue());
      }

      final int indentOptions1 = indentOptions;
      ApplicationManager.getApplication().runWriteAction(
        new Runnable() {
          public void run() {
            switch (indentOptions1) {
              case CodeInsightSettings.INDENT_BLOCK:
                if (!indented.get()) {
                  indentBlock(project, editor, bounds.getStartOffset(), bounds.getEndOffset(), blockIndentAnchorColumn);
                }
                break;

              case CodeInsightSettings.INDENT_EACH_LINE:
                if (!indented.get()) {
                  indentEachLine(project, editor, bounds.getStartOffset(), bounds.getEndOffset());
                }
                break;

              case CodeInsightSettings.REFORMAT_BLOCK:
                indentEachLine(project, editor, bounds.getStartOffset(), bounds.getEndOffset()); // this is needed for example when inserting a comment before method
                reformatBlock(project, editor, bounds.getStartOffset(), bounds.getEndOffset());
                break;
            }
          }
        }
      );

      if (bounds.isValid()) {
        caretModel.moveToOffset(bounds.getEndOffset());
        editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
        selectionModel.removeSelection();
        editor.putUserData(EditorEx.LAST_PASTED_REGION, TextRange.create(bounds));
      }
    }
  }

  private static void indentBlock(Project project, Editor editor, int startOffset, int endOffset, int originalCaretCol) {
    Document document = editor.getDocument();
    CharSequence chars = document.getCharsSequence();
    boolean hasNewLine = false;

    for (int i = endOffset - 1; i >= startOffset; i--) {
      char c = chars.charAt(i);
      if (c == '\n' || c == '\r') {
        hasNewLine = true;
        break;
      }
      if (c != ' ' && c != '\t') return; // do not indent if does not end with line separator
    }

    if (!hasNewLine) return;
    int lineStart = CharArrayUtil.shiftBackwardUntil(chars, startOffset - 1, "\n\r") + 1;
    int spaceEnd = CharArrayUtil.shiftForward(chars, lineStart, " \t");
    if (startOffset <= spaceEnd) { // we are in starting spaces
      if (lineStart != startOffset) {
        String deletedS = chars.subSequence(lineStart, startOffset).toString();
        document.deleteString(lineStart, startOffset);
        startOffset = lineStart;
        endOffset -= deletedS.length();
        document.insertString(endOffset, deletedS);
        LogicalPosition pos = new LogicalPosition(editor.getCaretModel().getLogicalPosition().line, originalCaretCol);
        editor.getCaretModel().moveToLogicalPosition(pos);
      }

      PsiDocumentManager.getInstance(project).commitAllDocuments();
      PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
      if (LanguageFormatting.INSTANCE.forContext(file) != null) {
        indentBlockWithFormatter(project, document, startOffset, endOffset, file);
      }
      else {
        indentPlainTextBlock(document, startOffset, endOffset, originalCaretCol);
      }
    }
  }

  private static void indentEachLine(Project project, Editor editor, int startOffset, int endOffset) {
    Document document = editor.getDocument();
    CharSequence chars = document.getCharsSequence();
    endOffset = CharArrayUtil.shiftBackward(chars, endOffset - 1, "\n\r") + 1;

    PsiDocumentManager.getInstance(project).commitAllDocuments();
    PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);

    CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(project);
    try {
      codeStyleManager.adjustLineIndent(file, new TextRange(startOffset, endOffset));
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
    }
  }

  private static void reformatBlock(Project project, Editor editor, int startOffset, int endOffset) {
    PsiDocumentManager.getInstance(project).commitAllDocuments();
    PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());

    try {
      CodeStyleManager.getInstance(project).reformatRange(file, startOffset, endOffset, true);
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
    }
  }

  private static void indentPlainTextBlock(Document document, int startOffset, int endOffset, int indentLevel) {
    CharSequence chars = document.getCharsSequence();
    int spaceEnd = CharArrayUtil.shiftForward(chars, startOffset, " \t");
    if (spaceEnd > endOffset) return;
    if (indentLevel == 0) return;

    char[] fill = new char[indentLevel];
    Arrays.fill(fill, ' ');
    String indentString = new String(fill);

    int offset = spaceEnd;
    while (true) {
      document.insertString(offset, indentString);
      chars = document.getCharsSequence();
      endOffset += indentLevel;
      offset = CharArrayUtil.shiftForwardUntil(chars, offset, "\n") + 1;
      if (offset >= endOffset || offset >= document.getTextLength()) break;
    }
  }

  private static void indentBlockWithFormatter(Project project, Document document,
                                               int startOffset,
                                               int endOffset,
                                               PsiFile file) {
    final FileType fileType = file.getFileType();
    CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(project);
    CharSequence chars = document.getCharsSequence();
    int spaceStart = CharArrayUtil.shiftBackwardUntil(chars, startOffset - 1, "\n\r") + 1;
    int spaceEnd = CharArrayUtil.shiftForward(chars, startOffset, " \t");
    if (spaceEnd > endOffset) return;
    while (!codeStyleManager.isLineToBeIndented(file, spaceEnd)) {
      spaceStart = CharArrayUtil.shiftForwardUntil(chars, spaceEnd, "\n\r");
      spaceStart = CharArrayUtil.shiftForward(chars, spaceStart, "\n\r");
      spaceEnd = CharArrayUtil.shiftForward(chars, spaceStart, " \t");
      if (spaceEnd >= endOffset) return;
    }

    Indent indent = codeStyleManager.getIndent(chars.subSequence(spaceStart, spaceEnd).toString(), fileType);
    int newEnd = codeStyleManager.adjustLineIndent(document, spaceEnd);
    chars = document.getCharsSequence();
    if (spaceStart > newEnd) {
      newEnd = spaceStart; //TODO lesya. Try to reproduce it. SCR52139
    }
    Indent newIndent = codeStyleManager.getIndent(chars.subSequence(spaceStart, newEnd).toString(), fileType);
    Indent indentShift = newIndent.subtract(indent);
    ArrayList<Boolean> indentFlags = new ArrayList<Boolean>();
    if (!indentShift.isZero()) {
      //System.out.println("(paste block) old indent = " + indent);
      //System.out.println("(paste block) new indent = " + newIndent);
      endOffset += newEnd - spaceEnd;

      int offset = newEnd;
      while (true) {
        offset = CharArrayUtil.shiftForwardUntil(chars, offset, "\n\r");
        if (offset >= endOffset) break;
        offset = CharArrayUtil.shiftForward(chars, offset, "\n\r");
        if (offset >= endOffset) break;
        int offset1 = CharArrayUtil.shiftForward(chars, offset, " \t");
        if (offset1 >= endOffset) break;
        if (chars.charAt(offset1) == '\n' || chars.charAt(offset1) == '\r') { // empty line
          offset = offset1;
          continue;
        }
        Boolean flag = codeStyleManager.isLineToBeIndented(file, offset) ? Boolean.TRUE : Boolean.FALSE;
        indentFlags.add(flag);
        offset = offset1;
      }

      offset = newEnd;
      int count = 0;
      while (true) {
        offset = CharArrayUtil.shiftForwardUntil(chars, offset, "\n\r");
        if (offset >= endOffset) break;
        offset = CharArrayUtil.shiftForward(chars, offset, "\n\r");
        if (offset >= endOffset) break;
        int offset1 = CharArrayUtil.shiftForward(chars, offset, " \t");
        if (offset1 >= endOffset) break;
        if (chars.charAt(offset1) == '\n' || chars.charAt(offset1) == '\r') { // empty line
          offset = offset1;
          continue;
        }
        int index = count++;
        if (indentFlags.get(index) == Boolean.FALSE) {
          offset = offset1;
          continue;
        }
        String space = chars.subSequence(offset, offset1).toString();
        indent = codeStyleManager.getIndent(space, fileType);
        indent = indent.add(indentShift);
        String newSpace = codeStyleManager.fillIndent(indent, fileType);
        document.replaceString(offset, offset1, newSpace);
        chars = document.getCharsSequence();
        offset += newSpace.length();
        endOffset += newSpace.length() - space.length();
      }
    }
  }
}

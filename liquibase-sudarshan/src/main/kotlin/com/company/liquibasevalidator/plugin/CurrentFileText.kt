package com.company.liquibasevalidator.plugin

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * The text of [file] **as the developer currently sees it**: the in-memory Document when the
 * file is open with unsaved edits, falling back to the saved content otherwise.
 *
 * `VfsUtilCore.loadText` alone reads what is on disk, so a file edited but not yet saved was
 * validated in its previous state and reported clean — the editor inspection (which reads the
 * PSI/Document) flagged the problem while the report, the dry run and the pre-commit check did
 * not. Call inside a read action.
 */
internal fun currentTextOf(file: VirtualFile): String =
    FileDocumentManager.getInstance().getDocument(file)?.text ?: VfsUtilCore.loadText(file)

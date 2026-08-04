// Liquibase Sudarshan for VS Code — runs the standalone CLI and maps its findings to
// native VS Code diagnostics (inline squiggles + Problems panel).
// Plain JavaScript, zero dependencies: compatible with VS Code 1.60+.
'use strict';

const vscode = require('vscode');
const cp = require('child_process');
const path = require('path');
const fs = require('fs');

const FINDING = /^(.+):(\d+):(\d+):\s+(error|warning|info):\s+(.+)$/;

let diagnostics;
let output;
let running = false;
let queued = false;

function config() {
    return vscode.workspace.getConfiguration('liquibaseSudarshan');
}

function repositoryPath() {
    const configured = config().get('repositoryPath');
    if (configured) return configured;
    const folders = vscode.workspace.workspaceFolders;
    return folders && folders.length > 0 ? folders[0].uri.fsPath : undefined;
}

function severityOf(label) {
    if (label === 'error') return vscode.DiagnosticSeverity.Error;
    if (label === 'warning') return vscode.DiagnosticSeverity.Warning;
    return vscode.DiagnosticSeverity.Information;
}

function validate() {
    if (running) { queued = true; return; }
    const repo = repositoryPath();
    const jar = config().get('cliJar');
    if (!repo) return;
    if (!jar || !fs.existsSync(jar)) {
        vscode.window.showWarningMessage(
            'Liquibase Sudarshan: set "liquibaseSudarshan.cliJar" to the CLI jar ' +
            '(build it with: gradlew cliJar in the liquibase-sudarshan project).');
        return;
    }

    running = true;
    const args = ['-jar', jar, repo].concat(config().get('extraArgs') || []);
    const java = config().get('javaPath') || 'java';
    output.appendLine('[liquibase-sudarshan] ' + java + ' ' + args.join(' '));

    const child = cp.spawn(java, args, { cwd: repo });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });
    child.on('error', (err) => {
        running = false;
        vscode.window.showErrorMessage('Liquibase Sudarshan: cannot run Java: ' + err.message);
    });
    child.on('close', () => {
        running = false;
        output.append(stdout);
        if (stderr) output.append(stderr);

        const byFile = new Map();
        for (const line of stdout.split(/\r?\n/)) {
            const match = FINDING.exec(line);
            if (!match) continue;
            const file = match[1];
            const lineNo = Math.max(0, parseInt(match[2], 10) - 1);
            const colNo = Math.max(0, parseInt(match[3], 10) - 1);
            const diagnostic = new vscode.Diagnostic(
                new vscode.Range(lineNo, colNo, lineNo, colNo + 1),
                match[5],
                severityOf(match[4]));
            diagnostic.source = 'liquibase-sudarshan';
            const key = path.normalize(file);
            if (!byFile.has(key)) byFile.set(key, []);
            byFile.get(key).push(diagnostic);
        }

        diagnostics.clear();
        for (const entry of byFile.entries()) {
            diagnostics.set(vscode.Uri.file(entry[0]), entry[1]);
        }

        const summary = (stdout.match(/^Liquibase Sudarshan: .*$/m) || [null])[0];
        if (summary) vscode.window.setStatusBarMessage(summary, 8000);

        if (queued) { queued = false; validate(); }
    });
}

function activate(context) {
    diagnostics = vscode.languages.createDiagnosticCollection('liquibase-sudarshan');
    output = vscode.window.createOutputChannel('Liquibase Sudarshan');
    context.subscriptions.push(diagnostics, output);

    context.subscriptions.push(
        vscode.commands.registerCommand('liquibaseSudarshan.validateRepository', validate),
        vscode.commands.registerCommand('liquibaseSudarshan.clearDiagnostics', () => diagnostics.clear()),
        vscode.workspace.onDidSaveTextDocument((doc) => {
            if (config().get('runOnSave') && doc.fileName.toLowerCase().endsWith('.sql')) {
                validate();
            }
        }));
}

function deactivate() { }

module.exports = { activate, deactivate };

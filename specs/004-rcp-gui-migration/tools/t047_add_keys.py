# T047 (one-shot): inserts the new/missing RCP UI keys into the localization
# catalogs, keeping alphabetical placement. Idempotent: skips keys already
# present. Run from the repo root with: python specs/004-rcp-gui-migration/tools/t047_add_keys.py
import io
import os

ROOT = "iped-app/resources/localization"

DESKTOP_NEW = {
    "": {
        "RcpMenu.View": "View",
        "RcpMenu.Theme": "Theme",
        "RcpMenu.Theme.System": "System",
        "RcpMenu.Theme.Light": "Light",
        "RcpMenu.Theme.Dark": "Dark",
        "RcpMenu.UiScale": "UI Scale...",
        "RcpParts.Viewer": "Viewer",
        "RcpParts.Timeline": "Timeline",
        "RcpParts.RelatedItems": "Related Items",
        "RcpTheme.RestartInfo.title": "Theme changed",
        "RcpTheme.RestartInfo.message": "The theme preference was saved. Restart the application for it to take full effect.",
    },
    "_pt_BR": {
        "RcpMenu.View": "Exibir",
        "RcpMenu.Theme": "Tema",
        "RcpMenu.Theme.System": "Sistema",
        "RcpMenu.Theme.Light": "Claro",
        "RcpMenu.Theme.Dark": "Escuro",
        "RcpMenu.UiScale": "Escala da interface...",
        "RcpParts.Viewer": "Visualizador",
        "RcpParts.Timeline": "Linha do tempo",
        "RcpParts.RelatedItems": "Itens relacionados",
        "RcpTheme.RestartInfo.title": "Tema alterado",
        "RcpTheme.RestartInfo.message": "A preferência de tema foi salva. Reinicie a aplicação para que tenha efeito completo.",
    },
    "_de_DE": {
        "AppLifeCycle.openError.title": "Fehler beim Öffnen des Falls",
        "AppLifeCycle.selectCase.message": "Wählen Sie den Ordner eines IPED-Falls zum Öffnen aus",
        "AppLifeCycle.selectCase.title": "Fall öffnen",
        "ColumnsDialog.MoveUp": "Nach oben",
        "ColumnsDialog.MoveDown": "Nach unten",
        "ContentViewer.NextHit": "Nächster Treffer",
        "ContentViewer.PrevHit": "Vorheriger Treffer",
        "ExportItems.Exporting": "Elemente werden exportiert...",
        "ExportItems.ChooseFolder": "Zielordner auswählen",
        "ResultsTable.Sorting": "Sortieren...",
        "SearchBar.Run": "Suchen",
        "SearchBar.Hint": "Geben Sie eine Abfrage in der aktuellen Suchsyntax ein",
        "SearchBar.Searching": "Suche läuft...",
        "RcpMenu.View": "Ansicht",
        "RcpMenu.Theme": "Design",
        "RcpMenu.Theme.System": "System",
        "RcpMenu.Theme.Light": "Hell",
        "RcpMenu.Theme.Dark": "Dunkel",
        "RcpMenu.UiScale": "UI-Skalierung...",
        "RcpParts.Viewer": "Viewer",
        "RcpParts.Timeline": "Zeitachse",
        "RcpParts.RelatedItems": "Verknüpfte Elemente",
        "RcpTheme.RestartInfo.title": "Design geändert",
        "RcpTheme.RestartInfo.message": "Die Design-Einstellung wurde gespeichert. Starten Sie die Anwendung neu, damit sie vollständig wirksam wird.",
    },
    "_es_AR": {
        "AppLifeCycle.openError.title": "Error al abrir el caso",
        "AppLifeCycle.selectCase.message": "Seleccione la carpeta de un caso IPED para abrir",
        "AppLifeCycle.selectCase.title": "Abrir caso",
        "ColumnsDialog.MoveUp": "Mover hacia arriba",
        "ColumnsDialog.MoveDown": "Mover hacia abajo",
        "ContentViewer.NextHit": "Siguiente ocurrencia",
        "ContentViewer.PrevHit": "Ocurrencia anterior",
        "ExportItems.Exporting": "Exportando elementos...",
        "ExportItems.ChooseFolder": "Elija la carpeta de destino",
        "ResultsTable.Sorting": "Ordenando...",
        "SearchBar.Run": "Buscar",
        "SearchBar.Hint": "Escriba una consulta usando la sintaxis de búsqueda actual",
        "SearchBar.Searching": "Buscando...",
        "RcpMenu.View": "Ver",
        "RcpMenu.Theme": "Tema",
        "RcpMenu.Theme.System": "Sistema",
        "RcpMenu.Theme.Light": "Claro",
        "RcpMenu.Theme.Dark": "Oscuro",
        "RcpMenu.UiScale": "Escala de la interfaz...",
        "RcpParts.Viewer": "Visor",
        "RcpParts.Timeline": "Línea de tiempo",
        "RcpParts.RelatedItems": "Elementos relacionados",
        "RcpTheme.RestartInfo.title": "Tema cambiado",
        "RcpTheme.RestartInfo.message": "La preferencia de tema fue guardada. Reinicie la aplicación para que tenga efecto completo.",
    },
    "_fr_FR": {
        "AppLifeCycle.openError.title": "Erreur lors de l'ouverture du cas",
        "AppLifeCycle.selectCase.message": "Sélectionnez le dossier d'un cas IPED à ouvrir",
        "AppLifeCycle.selectCase.title": "Ouvrir un cas",
        "ColumnsDialog.MoveUp": "Monter",
        "ColumnsDialog.MoveDown": "Descendre",
        "ContentViewer.NextHit": "Occurrence suivante",
        "ContentViewer.PrevHit": "Occurrence précédente",
        "ExportItems.Exporting": "Exportation des éléments...",
        "ExportItems.ChooseFolder": "Choisissez le dossier de destination",
        "ResultsTable.Sorting": "Tri en cours...",
        "SearchBar.Run": "Rechercher",
        "SearchBar.Hint": "Saisissez une requête avec la syntaxe de recherche actuelle",
        "SearchBar.Searching": "Recherche en cours...",
        "RcpMenu.View": "Affichage",
        "RcpMenu.Theme": "Thème",
        "RcpMenu.Theme.System": "Système",
        "RcpMenu.Theme.Light": "Clair",
        "RcpMenu.Theme.Dark": "Sombre",
        "RcpMenu.UiScale": "Échelle de l'interface...",
        "RcpParts.Viewer": "Visionneuse",
        "RcpParts.Timeline": "Chronologie",
        "RcpParts.RelatedItems": "Éléments liés",
        "RcpTheme.RestartInfo.title": "Thème modifié",
        "RcpTheme.RestartInfo.message": "La préférence de thème a été enregistrée. Redémarrez l'application pour qu'elle prenne pleinement effet.",
    },
    "_it_IT": {
        "AppLifeCycle.openError.title": "Errore durante l'apertura del caso",
        "AppLifeCycle.selectCase.message": "Selezionare la cartella di un caso IPED da aprire",
        "AppLifeCycle.selectCase.title": "Apri caso",
        "ColumnsDialog.MoveUp": "Sposta su",
        "ColumnsDialog.MoveDown": "Sposta giù",
        "ContentViewer.NextHit": "Occorrenza successiva",
        "ContentViewer.PrevHit": "Occorrenza precedente",
        "ExportItems.Exporting": "Esportazione elementi...",
        "ExportItems.ChooseFolder": "Scegliere la cartella di destinazione",
        "ResultsTable.Sorting": "Ordinamento...",
        "SearchBar.Run": "Cerca",
        "SearchBar.Hint": "Digitare una query usando la sintassi di ricerca attuale",
        "SearchBar.Searching": "Ricerca in corso...",
        "RcpMenu.View": "Visualizza",
        "RcpMenu.Theme": "Tema",
        "RcpMenu.Theme.System": "Sistema",
        "RcpMenu.Theme.Light": "Chiaro",
        "RcpMenu.Theme.Dark": "Scuro",
        "RcpMenu.UiScale": "Scala dell'interfaccia...",
        "RcpParts.Viewer": "Visualizzatore",
        "RcpParts.Timeline": "Sequenza temporale",
        "RcpParts.RelatedItems": "Elementi correlati",
        "RcpTheme.RestartInfo.title": "Tema modificato",
        "RcpTheme.RestartInfo.message": "La preferenza del tema è stata salvata. Riavviare l'applicazione perché abbia pieno effetto.",
    },
}

ENGINE_NEW = {
    "_de_DE": {
        "ProgressWindow.Abort": "Abbrechen",
        "ProgressWindow.AbortConfirm": "Verarbeitung abbrechen? Der Fall bleibt unvollständig und die Verarbeitung muss später fortgesetzt oder neu gestartet werden.",
        "ProgressWindow.CloseConfirm": "Fortschrittsfenster schließen? Die Verarbeitung läuft im Hintergrund WEITER. Zum Anhalten die Schaltfläche „Abbrechen“ verwenden.",
        "ProgressWindow.ItemSpeed": "Durchschnittliche Elemente/s",
        "ProgressWindow.OpenUiError": "Fehler beim Starten der Analyse-Oberfläche:",
        "ProgressWindow.UiNotFound": "Analyse-Oberfläche nicht gefunden. Erwartet unter <Fall>/iped/ui oder <Installation>/ui; für Entwicklungsläufe -Diped.rcp.ui.home oder IPED_RCP_UI_HOME setzen.",
    },
    "_es_AR": {
        "ProgressWindow.Abort": "Abortar",
        "ProgressWindow.AbortConfirm": "¿Abortar el procesamiento? El caso quedará incompleto y el procesamiento deberá ser retomado o reiniciado más tarde.",
        "ProgressWindow.CloseConfirm": "¿Cerrar la ventana de progreso? El procesamiento CONTINUARÁ en segundo plano. Para detenerlo, use el botón Abortar.",
        "ProgressWindow.ItemSpeed": "Promedio de elementos/s",
        "ProgressWindow.OpenUiError": "Error al iniciar la interfaz de análisis:",
        "ProgressWindow.UiNotFound": "Interfaz de análisis no encontrada. Se esperaba en <caso>/iped/ui o <instalación>/ui; en desarrollo, defina -Diped.rcp.ui.home o IPED_RCP_UI_HOME.",
    },
    "_fr_FR": {
        "ProgressWindow.Abort": "Abandonner",
        "ProgressWindow.AbortConfirm": "Abandonner le traitement ? Le cas restera incomplet et le traitement devra être repris ou relancé plus tard.",
        "ProgressWindow.CloseConfirm": "Fermer la fenêtre de progression ? Le traitement CONTINUERA en arrière-plan. Pour l'arrêter, utilisez le bouton Abandonner.",
        "ProgressWindow.ItemSpeed": "Moyenne d'éléments/s",
        "ProgressWindow.OpenUiError": "Erreur au lancement de l'interface d'analyse :",
        "ProgressWindow.UiNotFound": "Interface d'analyse introuvable. Attendue dans <cas>/iped/ui ou <installation>/ui ; en développement, définissez -Diped.rcp.ui.home ou IPED_RCP_UI_HOME.",
    },
    "_it_IT": {
        "ProgressWindow.Abort": "Interrompi",
        "ProgressWindow.AbortConfirm": "Interrompere l'elaborazione? Il caso resterà incompleto e l'elaborazione dovrà essere ripresa o riavviata in seguito.",
        "ProgressWindow.CloseConfirm": "Chiudere la finestra di avanzamento? L'elaborazione CONTINUERÀ in background. Per fermarla, usare il pulsante Interrompi.",
        "ProgressWindow.ItemSpeed": "Media elementi/s",
        "ProgressWindow.OpenUiError": "Errore nell'avvio dell'interfaccia di analisi:",
        "ProgressWindow.UiNotFound": "Interfaccia di analisi non trovata. Attesa in <caso>/iped/ui o <installazione>/ui; in sviluppo, impostare -Diped.rcp.ui.home o IPED_RCP_UI_HOME.",
    },
}


def insert_sorted(path, new_entries):
    with io.open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    existing = {l.split("=", 1)[0].strip() for l in lines if "=" in l and not l.lstrip().startswith("#")}
    added = 0
    for key in sorted(new_entries):
        if key in existing:
            continue
        value = new_entries[key]
        entry = f"{key}={value}"
        index = len(lines)
        for i, line in enumerate(lines):
            if "=" in line and not line.lstrip().startswith("#"):
                line_key = line.split("=", 1)[0].strip()
                if line_key.lower() > key.lower():
                    index = i
                    break
        lines.insert(index, entry)
        added += 1
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    return added


def main():
    total = 0
    for suffix, entries in DESKTOP_NEW.items():
        path = os.path.join(ROOT, f"iped-desktop-messages{suffix}.properties")
        n = insert_sorted(path, entries)
        print(f"{path}: +{n}")
        total += n
    for suffix, entries in ENGINE_NEW.items():
        path = os.path.join(ROOT, f"iped-engine-messages{suffix}.properties")
        n = insert_sorted(path, entries)
        print(f"{path}: +{n}")
        total += n
    print(f"total added: {total}")


if __name__ == "__main__":
    main()

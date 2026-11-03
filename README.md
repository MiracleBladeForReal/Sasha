# Galaxy Sensor Lab

Prototipo Android in Kotlin, Jetpack Compose e Media3: seleziona un video dal telefono e riproducilo nell’app.

Non sono ancora implementati il modello fisico del rumore dei sensori, l’emulazione Samsung ISP o l’esportazione video.

## Scaricare e installare dal telefono

1. Accedi a GitHub e apri [Actions](https://github.com/MiracleBladeForReal/Sasha/actions/workflows/android.yml).
2. Apri l’ultima esecuzione verde di **Build Android APK**.
3. In **Artifacts**, tocca **GalaxySensorLab-debug-apk** e scarica lo ZIP.
4. Apri lo ZIP con **Archivio** di Samsung ed estrai `app-debug.apk`.
5. Tocca l’APK e, se richiesto, consenti l’installazione da questa fonte nelle impostazioni Android.
6. Installa e apri **Galaxy Sensor Lab**.

Richiede Android 8.0 o successivo. È un APK di sviluppo firmato automaticamente con una chiave debug, adatto alle prove sul telefono. Compilazioni eseguite su runner diversi possono avere chiavi debug diverse: se Android rifiuta un aggiornamento per firma incompatibile, disinstalla la versione di prova precedente (perdendo i suoi dati) e installa la nuova.

## Compilazione automatica

Ogni push su `main` e ogni pull request avvia compilazione e lint. Per avviarla a mano: **Actions → Build Android APK → Run workflow → main → Run workflow**. Gli artefatti rimangono disponibili per 30 giorni.

La pipeline usa Java 17, Gradle 8.9, Android SDK 35 e Android Gradle Plugin 8.7.3. Non richiede un PC, chiavi personali o servizi a pagamento per la firma debug. Per una versione di produzione servirà una chiave di firma stabile.

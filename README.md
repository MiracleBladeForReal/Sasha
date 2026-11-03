# Galaxy Sensor Lab Edge Capture 0.4

Terza app Android, derivata da **Grain 0.3** (commit `4a6eb7ce037ccc66bc2a720bcbce4514df2b5fad`), con profilo predefinito **Samsung Galaxy S6 Edge SIDD**. Ramo `edge-capture-v0.4`. Identificativo `com.galaxysensorlab.edgecapture`, nome **Galaxy Sensor Lab Edge Capture**: si installa insieme alle altre due app, con dati separati.

`main` (0.2, `206e1d0896eeccda3372424d76dfc341b6e043d9`) e `natural-grain-v0.3` restano intatti. Nessun file video originale viene modificato; elaborazione interamente sul telefono.

## Rumore di ripresa

- Rumore fotonico dipendente dal segnale: varianza base `a * segnale`. Poisson a basso conteggio, approssimazione gaussiana da lambda 12. Campionamento Poisson tramite prodotto di uniformi, massimo 64 iterazioni con fallback gaussiano.
- Componente di lettura indipendente dal segnale: `max(b,0) + readNoise²`. Il valore aggiunto è **creativo e non misurato**: gli originali SIDD S6 hanno `b = 0`, conservato senza modifiche.
- Quantità separata per luminosità e colore. Il colore usa campi RGB indipendenti, più larghi, con sottrazione della componente luminanza.
- Dimensione 0.5–12 px riferita a 1080p (minimo 1 pixel sorgente); morbidezza e interpolazione spaziale normalizzata per mantenere la varianza del campo a segnale costante.
- Ombre e alte luci con curve distinte e regolabili. Le soglie sono in RGB lineare della pipeline, non livelli RAW misurati.
- Rinnovo 1–60 volte al secondo basato sul timestamp della clip. Coerenza temporale tramite campo corrente + coefficiente × campo precedente, normalizzati. È un modello MA(1) del rumore: la correlazione ideale fra passi adiacenti è `c/(1+c²)`, fino a 0.5. Non fonde i fotogrammi del video e non implementa un denoise temporale OEM.
- **Nuova ripresa** genera un seme con `SecureRandom` Android. Ogni importazione genera una nuova realizzazione. Lo shader usa il seme salvato e il timestamp, quindi anteprima ed esportazione riutilizzano la stessa sequenza (con possibili differenze di conversione colore, HDR, risoluzione e codec). Preset e reset mantengono il seme.
- Spegnendo **Simulazione di ripresa** si torna al campo gaussiano naturale della 0.3; spegnendo **Rumore di ripresa** si usa il percorso classico. Il generatore è aggiornato in questa edizione, quindi non si promette identità pixel per pixel con i vecchi APK.

La distribuzione finale è modificata dalla miscela luma/croma, correlazione spaziale/temporale, curve creative e clipping. Non è un modello calibrato dell’intera fotocamera.

## Altre funzioni conservate

439 profili selezionabili con ricerca: S6 Edge SIDD e 438 modelli darktable (incluse fotocamere Canon, Nikon, Sony, Fujifilm e telefoni OnePlus/LG). La scelta modifica realmente i coefficienti RGB. ISO limitati all’intervallo pubblicato; interpolazione log ISO per SIDD, lineare ISO per darktable. Duplicati ISO: prima misura pubblicata. Il JSON originale darktable rimane completo.

Importazione video Android, anteprima anche in pausa, prima/dopo, bypass originale, preset, esposizione, temperatura relativa, saturazione, contrasto, denoise spaziale, nitidezza, vignettatura. Salvataggio MP4 H.264/AAC a 720p/1080p senza ingrandimento; bitrate richiesto 8/16 Mbps. Avanzamento, annullamento, apertura e condivisione del risultato. In esportazione viene elaborato l’intero fotogramma, senza linea di confronto. Impostazioni salvate automaticamente.

## Video di riferimento trovati online

Fonti e collegamenti pubblicati verificati il 3 novembre 2026; **non sono stati scaricati né analizzati statisticamente in questa sessione**. Non presentiamo una calibrazione ricavata da questi filmati.

- [GSMArena: recensione video S6 Edge](https://www.gsmarena.com/samsung_galaxy_s6_edge-review-1236p9.php): il sito dichiara campioni originali, non modificati, direttamente dal telefono. [MP4 1080p/30](https://fdn.gsmarena.com/vv/reviewsimg/samsung-galaxy-s6-edge/camera/gsmarena_v002.mp4), [MP4 2160p/30](https://fdn.gsmarena.com/vv/reviewsimg/samsung-galaxy-s6-edge/camera/gsmarena_v001.mp4). Originale video compresso dal telefono, non RAW.
- [DPReview: recensione fotocamera S6 Edge](https://www.dpreview.com/reviews/2097209656/samsung-galaxy-s6-edge-camera-review/): [diurno 1080p](https://www.youtube.com/watch?v=vJssgz1gokM), [scarsa luce](https://www.youtube.com/watch?v=T995ZgOwhVA), [4K](https://www.youtube.com/watch?v=TQc_MGz7Rq0). Copie distribuite su YouTube, ricompresse: utili al confronto visivo, inadatte a misurare direttamente il rumore RAW.

Nell’app i riferimenti sono accessibili da **Dati, fonte e licenze**. I filmati restano dei rispettivi autori e non sono inclusi nell’APK. Per una futura calibrazione video controllata servono riprese originali con superfici uniformi, impostazioni note e più condizioni di illuminazione; un normale filmato non separa automaticamente texture, compressione e rumore del sensore.

## Installazione dal Samsung

1. Apri [Actions del ramo Edge Capture](https://github.com/MiracleBladeForReal/Sasha/actions?query=branch%3Aedge-capture-v0.4) e l’ultima build verde.
2. Scarica **GalaxySensorLab-Edge-Capture-debug-apk** in **Artifacts**.
3. In **Archivio**, estrai lo ZIP e apri `app-debug.apk`. Consenti l’installazione da quella fonte quando Android lo richiede.

Android 8 o successivo. Non disinstallare le altre app: questa ha un identificativo differente. APK debug; per aggiornamenti stabili nel tempo servirà una chiave di firma di produzione.

Importa un video, lascia il profilo S6 Edge, regola intensità/ISO e prova **Nuova ripresa**. Le impostazioni modificano il risultato reale esportato. Tieni l’app aperta durante il salvataggio; per clip impegnative prova 720p e una sorgente MP4 H.264 SDR. Non sono ancora misurate velocità o temperatura su un Samsung fisico. Il campionamento fotonico richiede più calcoli della grana precedente.

## Fonti, licenze e limiti

SIDD: Abdelhamed, Lin, Brown, CVPR 2018, [tabella originale al commit fissato](https://github.com/AbdoKamel/abdokamel.github.io/blob/7759f112db725fb68cf89c28432cfd2baea90cb0/sidd/files/noise_level_functions.csv), MIT. Sei profili rappresentativi ISO 100–3200, coefficienti e identificativi scena in `sidd_s6.json`. Nessun valore S6 è sostituito con una calibrazione inventata.

[darktable](https://github.com/darktable-org/darktable/blob/879067695ea85f42d238a5c1603a021d14d71d3f/data/noiseprofiles.json), GPL-3.0-or-later, blob originale `086a62784df9e660a9cdcfc96d15c5302522502e`. Nella simulazione fotonica si limitano separatamente a zero i coefficienti negativi prima della scomposizione fisica; nei percorsi precedenti si limita la varianza totale `max(a * segnale + b,0)`. Il file dati non viene alterato.

Questa edizione è GPL-3.0-or-later, con testo integrale e attribuzioni nel repository e nell’APK. AndroidX/Media3: Apache 2.0. Il dialogo licenze collega il commit esatto di compilazione. Le versioni precedenti non vengono modificate da questo ramo.

Un MP4 contiene già sviluppo colore, denoise, nitidezza e compressione. Lo shader usa RGB lineare Media3 come approssimazione del segnale, senza ricostruire Bayer/RAW, ottica, movimento, rolling shutter o l’ISP proprietario Samsung. Non può trasformare un filmato AI in una ripresa autenticamente acquisita da un S6 Edge, né garantire identità statistica con quel dispositivo. Può aggiungere un rumore simulato più plausibile; difetti geometrici o movimenti incoerenti del video restano.

## Verifica automatica

GitHub Actions compila APK, test e lint e avvia Android 15 con GPU software. Prove con veri shader GL, codec Android e fotogrammi estratti dai MP4: rumore, esposizione, denoise, luma/croma, dimensione, rinnovo, riproducibilità/semi differenti, componente nelle zone nere, curve ombre/alte luci, dipendenza dal segnale, correlazione temporale. Verifiche anche su coefficienti originali, catalogo, selezione profilo via UI, importazione/anteprima in pausa/esportazione tramite il modello applicativo. Report e schermata sono artefatti della build. Nessuna equivalenza con un test prestazionale sul Samsung fisico.

Stack conservato: Kotlin 2.0.21, Compose, Media3 1.5.1, Java 17, Gradle 8.9, AGP 8.7.3, SDK 35. Push su `edge-capture-v0.4`, PR o avvio manuale attivano la build.

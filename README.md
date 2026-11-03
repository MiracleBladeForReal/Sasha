# Galaxy Sensor Lab Edge Sensor 0.5

Edizione essenziale della terza app, richiesta per utilizzare solo i dati del rumore del sensore. Stesso identificativo `com.galaxysensorlab.edgecapture`; nome **Galaxy Sensor Lab Edge Sensor**. Sviluppo sul ramo `edge-capture-v0.4`, versione Android 0.5 (codice 5). `main` 0.2 e `natural-grain-v0.3` restano intatti.

## Cosa rimane

Importazione di video SDR, selezione del profilo e di un ISO pubblicato, anteprima con bypass o confronto metà/metà, esportazione MP4 H.264/AAC. Nessun ridimensionamento né cambio volontario della frequenza della sorgente. Il confronto è solo in anteprima; l’esportazione applica il modello all’intero fotogramma. Tutta l’elaborazione dell’app avviene sul telefono.

Rimangono 439 profili: Samsung Galaxy S6 Edge SIDD predefinito più 438 modelli darktable. Il selettore ISO propone esclusivamente i valori presenti nel profilo, senza cursore di intensità. Al cambio fotocamera l’ISO viene riportato al valore pubblicato più vicino.

**Rimossi dal codice e dall’interfaccia**: preset, intensità arbitraria, rumore di lettura aggiunto, rinforzo ombre, protezione alte luci, dimensione/morbidezza della grana, miscela artistica luminanza/crominanza, rinnovo e correlazione temporale regolabili, esposizione, temperatura, saturazione, contrasto, denoise, nitidezza e vignettatura. I vecchi valori salvati vengono scartati dalla migrazione: nessun effetto residuo nascosto. Nessuna conversione HDR automatica; il video HDR dichiarato viene rifiutato con un messaggio per evitare modifiche automatiche di luminosità.

## Modello del rumore

Per ogni canale RGB, in spazio lineare della pipeline Media3:

`varianza = max(a × segnale + b, 0)`

I coefficienti pubblicati sono usati senza moltiplicatori creativi. Il rumore fotonico è campionato Poisson ai bassi conteggi (`lambda = segnale / a`), con approssimazione gaussiana da lambda 12 e limite di 64 iterazioni/fallback. La componente gaussiana di lettura viene aggiunta solo se `b` pubblicato è positivo. Per intercette darktable negative si usa il residuo fotonico normalizzato scalato con la varianza totale, senza sostituire `b` con zero. Le approssimazioni di distribuzione non cambiano i coefficienti nel file.

Per S6 Edge i sei beta2 sono zero e restano zero: il nero esattamente nullo non riceve un rumore di lettura inventato. Non sono imposte correlazioni spaziali/temporali non misurate; i campioni sono per pixel, canale e timestamp video. Il seme casuale Android viene generato automaticamente all’importazione e conservato alla riapertura; anteprima ed esportazione riutilizzano la stessa realizzazione prima delle differenze introdotte dal codec. Il clipping all’uscita e la compressione possono modificare media/varianza vicino a nero e saturazione.

Il modello non contiene una curva di esposizione o sviluppo colore. Non si può però garantire l’identità luminosa pixel per pixel dopo l’aggiunta di rumore e una ricodifica video con perdita.

## Originali S6 Edge trovati

[GSMArena, sezione video della recensione](https://www.gsmarena.com/samsung_galaxy_s6_edge-review-1236p9.php) dichiara campioni non modificati direttamente dal telefono:

- [1080p30 originale MP4](https://fdn.gsmarena.com/vv/reviewsimg/samsung-galaxy-s6-edge/camera/gsmarena_v002.mp4)
- [2160p30 originale MP4](https://fdn.gsmarena.com/vv/reviewsimg/samsung-galaxy-s6-edge/camera/gsmarena_v001.mp4)

Collegamenti verificati il 3 novembre 2026 e accessibili nel dialogo dati dell’app. Non sono copie YouTube. Si tratta comunque di video compressi e sviluppati dal telefono, non di RAW; ISO effettivo e trattamento ISP non sono documentati nei campioni.

Il workflow **Verify original S6 Edge video reference** scarica il 1080p30, registra SHA-256 e metadati del contenitore, verifica la decodifica e riporta statistiche dei primi 15 fotogrammi alla risoluzione nativa nell’artefatto JSON. Il report segnala eventuali errori del sito. Non ridistribuisce il filmato. Le differenze fra fotogrammi includono movimento e compressione e **non vengono spacciate per una misura del rumore del sensore**. Nessun coefficiente viene stimato o sostituito automaticamente da quella scena.

**Limite concreto:** questo è un modello basato su dati di rumore RAW, applicato all’RGB video. Non ricostruisce Bayer/demosaicizzazione, risposta ottica, denoise e sharpening video proprietari, tone mapping o ISP Samsung. I campioni trovati permettono un riferimento video, non una calibrazione del sensore a ISO noto. La replica completa del telefono non è dimostrata e l’app non la dichiara.

## Installazione sul Samsung

1. Apri [Actions di questo ramo](https://github.com/MiracleBladeForReal/Sasha/actions?query=branch%3Aedge-capture-v0.4) e la build Android verde della 0.5.
2. Scarica l’artefatto **GalaxySensorLab-Edge-Sensor-debug-apk**, estrai lo ZIP con Archivio e apri `app-debug.apk`.
3. Autorizza l’installazione da quella fonte se Android lo richiede.

La 0.5 sostituisce solo la terza app Edge Capture. Le altre due restano installate separatamente. APK debug: se Android segnala una firma incompatibile, disinstalla solo Edge Capture e installa Edge Sensor; le preferenze di quella app andranno perse. Per aggiornamenti stabili servirà una chiave di produzione.

Android 8 o successivo. Il codec del telefono deve supportare la risoluzione della sorgente; non viene applicato un ridimensionamento nascosto. Bitrate richiesto 16 Mbps fino a 1080p, 48 Mbps oltre: non è una calibrazione della compressione Samsung. Il rumore già contenuto nel video sorgente rimane presente. L’app non identifica automaticamente ISO o fotocamera da un video AI.

## Dati e licenze

SIDD: Abdelhamed, Lin, Brown, CVPR 2018, [tabella al commit fissato](https://github.com/AbdoKamel/abdokamel.github.io/blob/7759f112db725fb68cf89c28432cfd2baea90cb0/sidd/files/noise_level_functions.csv), MIT. Sei righe rappresentative ISO 100–3200 in `sidd_s6.json`, con coefficienti RGB e scena di origine conservati.

[darktable](https://github.com/darktable-org/darktable/blob/879067695ea85f42d238a5c1603a021d14d71d3f/data/noiseprofiles.json), GPL-3.0-or-later, blob originale `086a62784df9e660a9cdcfc96d15c5302522502e`, completo e inalterato. Per ISO duplicati si usa la prima misura pubblicata. L’API interna conserva l’interpolazione storica per compatibilità dei dati, ma l’interfaccia accetta solo ISO pubblicati.

Edizione GPL-3.0-or-later, testo completo e attribuzioni nell’APK; AndroidX/Media3 Apache 2.0. Dialogo licenze con collegamento al commit esatto della build.

## Verifica

Compilazione, Android lint e prove su Android 15 con codec/GL reali. Test su coefficienti pubblicati, migrazione delle vecchie impostazioni creative, selezione del profilo/ISO, realizzazioni riproducibili, nero S6 senza floor artificiale, media luminosa in spazio approssimativamente lineare, aumento della varianza con segnale e ISO, confronto, rifiuto HDR. Prova integrata di importazione, modifica in pausa, esportazione a dimensioni originali e ripristino della sequenza alla riapertura. Schermate e report sono artefatti della build. Il test su emulatore non misura fedeltà statistica o prestazioni sul Samsung fisico.

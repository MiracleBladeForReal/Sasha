# Galaxy Sensor Lab Pixel Bruciati 0.6

Nuova app basata sulla 0.5 del ramo `edge-capture-v0.4`, sviluppata esclusivamente su `burnt-pixels-v0.6`. I rami precedenti restano intatti. Identificativo separato `com.galaxysensorlab.burntpixels`, nome **Galaxy Sensor Lab Pixel Bruciati**: si installa accanto alla 0.5 senza sostituirla e conserva preferenze proprie.

## Pixel bruciati

Dopo aver importato un video SDR, attiva **Pixel difettosi** nella scheda **PIXEL BRUCIATI**. L'effetto è disattivato inizialmente. Puoi scegliere:

- Quantità da 1 a 200 punti, dimensione da 1 a 6 pixel della sorgente e intensità da 0 a 100%.
- Punti bianchi (hot/stuck), neri (dead), colorati RGB o misti.
- **Cambia disposizione** per generare un'altra mappa, salvata automaticamente con gli altri controlli.

La mappa resta fissa per tutta la clip: il timestamp, l'ISO, la fotocamera e il seme del rumore non spostano i difetti. Dimensione, colore e intensità non ne cambiano la posizione di ancoraggio; aumentando la quantità si conservano i punti già presenti. Anteprima ed esportazione usano lo stesso shader e la stessa mappa, applicata dopo il rumore del sensore. **Originale** esclude entrambi gli effetti, **Prima / dopo** preserva la metà originale. L'MP4 contiene l'intero fotogramma elaborato anche se l'anteprima è in bypass/confronto.

Questi difetti sono una simulazione creativa, non misure dei sensori SIDD/darktable. Il fotogramma viene suddiviso in 16 × 16 celle con un punto candidato per cella; una permutazione deterministica sceglie il numero richiesto. La dimensione viene limitata alla cella per sorgenti molto piccole; sotto 16 pixel su un lato l'effetto è escluso. La compressione H.264 e il ridimensionamento dell'anteprima possono attenuare i singoli punti: per una prima prova usa 4 px, intensità 100%, Bianchi. Non viene ridimensionata la sorgente.

## Cosa rimane

Importazione di video SDR, selezione del profilo e di un ISO pubblicato, anteprima con bypass o confronto metà/metà, esportazione MP4 H.264/AAC. Nessun ridimensionamento né cambio volontario della frequenza della sorgente. Il confronto è solo in anteprima; l’esportazione applica il modello all’intero fotogramma. Tutta l’elaborazione dell’app avviene sul telefono.

Rimangono 439 profili: Samsung Galaxy S6 Edge SIDD predefinito più 438 modelli darktable. Il selettore ISO propone esclusivamente i valori presenti nel profilo, senza cursore di intensità. Al cambio fotocamera l’ISO viene riportato al valore pubblicato più vicino.

**Assenti dal modello del rumore e dall’interfaccia**: preset, intensità arbitraria, rumore di lettura aggiunto, rinforzo ombre, protezione alte luci, dimensione/morbidezza della grana, miscela artistica luminanza/crominanza, rinnovo e correlazione temporale regolabili, esposizione, temperatura, saturazione, contrasto, denoise, nitidezza e vignettatura. I vecchi valori salvati vengono scartati dalla migrazione: nessun effetto residuo nascosto. Nessuna conversione HDR automatica; il video HDR dichiarato viene rifiutato con un messaggio per evitare modifiche automatiche di luminosità.

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

1. Apri [Actions di questo ramo](https://github.com/MiracleBladeForReal/Sasha/actions?query=branch%3Aburnt-pixels-v0.6) e la build **Build Galaxy Sensor Lab Pixel Bruciati APK** verde della 0.6.
2. Scarica l’artefatto **GalaxySensorLab-Pixel-Bruciati-debug-apk**, estrai lo ZIP con Archivio e apri `app-debug.apk`.
3. Autorizza l’installazione da quella fonte se Android lo richiede.

La 0.6 si installa separatamente dalla 0.5 e dalle altre app. APK debug: in caso di firma incompatibile durante un futuro aggiornamento della 0.6, disinstalla solo **Pixel Bruciati**; perderai le sue preferenze. Per aggiornamenti stabili servirà una chiave di produzione.

Android 8 o successivo. Il codec del telefono deve supportare la risoluzione della sorgente; non viene applicato un ridimensionamento nascosto. Bitrate richiesto 16 Mbps fino a 1080p, 48 Mbps oltre: non è una calibrazione della compressione Samsung. Il rumore già contenuto nel video sorgente rimane presente. L’app non identifica automaticamente ISO o fotocamera da un video AI.

## Dati e licenze

SIDD: Abdelhamed, Lin, Brown, CVPR 2018, [tabella al commit fissato](https://github.com/AbdoKamel/abdokamel.github.io/blob/7759f112db725fb68cf89c28432cfd2baea90cb0/sidd/files/noise_level_functions.csv), MIT. Sei righe rappresentative ISO 100–3200 in `sidd_s6.json`, con coefficienti RGB e scena di origine conservati.

[darktable](https://github.com/darktable-org/darktable/blob/879067695ea85f42d238a5c1603a021d14d71d3f/data/noiseprofiles.json), GPL-3.0-or-later, blob originale `086a62784df9e660a9cdcfc96d15c5302522502e`, completo e inalterato. Per ISO duplicati si usa la prima misura pubblicata. L’API interna conserva l’interpolazione storica per compatibilità dei dati, ma l’interfaccia accetta solo ISO pubblicati.

Edizione GPL-3.0-or-later, testo completo e attribuzioni nell’APK; AndroidX/Media3 Apache 2.0. Dialogo licenze con collegamento al commit esatto della build.

## Verifica

Compilazione, Android lint e prove su Android 15 con codec/GL reali. Test su coefficienti pubblicati, migrazione delle vecchie impostazioni creative, selezione del profilo/ISO, realizzazioni riproducibili, nero S6 senza floor artificiale, media luminosa in spazio approssimativamente lineare, aumento della varianza con segnale e ISO, confronto, rifiuto HDR. Prova integrata di importazione, modifica in pausa, esportazione a dimensioni originali e ripristino della sequenza alla riapertura. Schermate e report sono artefatti della build. Il test su emulatore non misura fedeltà statistica o prestazioni sul Samsung fisico.


Le prove della 0.6 verificano anche persistenza e limiti dei controlli, attivazione dall'interfaccia, punti visibili negli MP4 reali, posizione fissa tra timestamp e ISO/semi del rumore diversi, cambio disposizione, intensità zero, disattivazione, bypass e metà originale pulita.

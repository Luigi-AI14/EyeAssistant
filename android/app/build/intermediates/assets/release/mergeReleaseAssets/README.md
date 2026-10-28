# Modelli

Questa cartella e' vuota di proposito: i pesi non stanno nel controllo di versione.

Vengono depositati qui dagli script di training, che li copiano da soli:

```bash
python -m training.export_onnx      # -> floornet_fp32.onnx   (4.4 MB)
python -m training.quantize         # -> floornet_int8.onnx   (1.5 MB)
```

`FloorSegmenter` carica `floornet_int8.onnx` se presente, altrimenti `floornet_fp32.onnx`.
Senza nessuno dei due l'app parte lo stesso e mostra "Modello non caricato".

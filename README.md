# QuPath InstanSeg extension

[![Forum](https://img.shields.io/badge/forum-image.sc-green)](https://forum.image.sc/tag/qupath)

<img style="float: right" width="25%" alt="InstanSeg logo" src="https://github.com/instanseg/instanseg/raw/main/images/instanseg_logo.png" />

## 🚧 Work-in-progress! 🚧 

**The easiest way to try this out is with the [new QuPath 0.6.0 Release Candidate](https://github.com/qupath/qupath/releases)!**

---

**Welcome to the [InstanSeg](https://github.com/instanseg/instanseg) extension for [QuPath](http://qupath.github.io)!**

**InstanSeg** is a novel deep-learning-based method for segmenting nuclei and cells... and potentially much more.

> Looking for the main InstanSeg code for training models?
> Find it [here](https://github.com/instanseg/instanseg).

## What is InstanSeg?

You can learn more about InstanSeg in two preprints.

For an introduction & comparison to other approaches for _nucleus_ segmentation in _brightfield histology images_, see:

> Goldsborough, T. et al. (2024) ‘InstanSeg: an embedding-based instance segmentation algorithm optimized for accurate, efficient and portable cell segmentation’. _arXiv_. Available at: https://doi.org/10.48550/arXiv.2408.15954.

To read about InstanSeg's extension to _nucleus + full cell segmentation_ and support for _fluorescence & multiplexed_ images, see:

> Goldsborough, T. et al. (2024) ‘A novel channel invariant architecture for the segmentation of cells and nuclei in multiplexed images using InstanSeg’. _bioRxiv_, p. 2024.09.04.611150. Available at: https://doi.org/10.1101/2024.09.04.611150.

## Why should I try InstanSeg?

1. It's fully open-source
   - We also provide models pre-trained on open datasets
2. It's not limited to nuclei... or to cells
   - One model can provide different outputs: nuclei, cells, or both
3. It's accurate compared to all the popular alternative methods 
   - In our hands InstanSeg consistently achieved the best F1 score across multiple datasets compared to CellPose, StarDist, HoVerNet and Mesmer. But everyone's images are different & fair benchmarking is hard - check out the preprints & judge what works best for you!
4. It's faster than other methods (usually _much_ faster)
   - InstanSeg supports GPU acceleration with CUDA _and_ with Apple Silicon (so Mac users can finally have fast segmentation too!)
5. It's portable 
   - The full pipeline _including postprocessing_ compiles to TorchScript - so you can also run it from [Python](https://github.com/instanseg/instanseg) & [DeepImageJ](https://deepimagej.github.io).
6. It's easy to use
   - InstanSeg models are trained at a specific pixel resolution (e.g. 0.5 µm/px). As long as your image has pixel calibration set, this extension will deal with any resizing needed to keep InstanSeg happy.
7. It's _uniquely_ easy to use for fluorescence & multiplexed images 
   - When used with ChannelNet, InstanSeg supports _any* number of channels in any order_! There's no need to select channels manually, or retrain for different markers.

_*-Well, at least as much as your computer's memory allows_

## How do I _get_ InstanSeg in QuPath?

This extension is for **QuPath v0.6.0... which we plan to release in October 2024**.

If you can't wait, you can try the [release candidate v0.6.0-rc1](https://github.com/qupath/qupath/releases) - which comes with this extension pre-installed, along with the [Deep Java Library Extension](https://github.com/qupath/qupath-extension-djl).

> **GPU support**
> If you have an NVIDIA graphics card & want CUDA support, check out [GPU support](https://qupath.readthedocs.io/en/0.5/docs/deep/gpu.html).
> 
> If you use a recent Mac with Apple silicon, no special configuration should be needed - just choose 'MPS' as the device (described below).

## How do I _run_ InstanSeg in QuPath?

There are two steps to get started:
1. Use _Extensions → Deep Java Library → Manage DJL Engines_ to download PyTorch
   - See [docs here](https://qupath.readthedocs.io/en/stable/docs/deep/djl.html#getting-started-with-qupath-djl) - especially if you want it to work with CUDA (which can be tricky to configure)
2. Use _Extensions → InstanSeg → Run InstanSeg_ to launch InstanSeg

The dialog should guide you through what to do next:
1. Choose a directory on your computer to download pre-trained models
2. Pick a model and download it
3. Select one or more annotations
4. Press *Run*

## What do the additional options do?

There are several options available to customize things:

* **Preferred device**: `cpu` to run without a graphics card involved, `gpu` if you've been lucky with CUDA configuration, and `mps` if you're using Apple Silicon
* **Threads**: Number of threads to use to use for fetching & submitting image tiles; 1 is usually too little, but high numbers probably won't help much - so the default is between 2 and 4.
* **Tile size**: Large regions are broken into tiles; usually 512 or 1024 pixels is a good choice
* **Tile padding**: When creating tiles, part of each tile is used as 'padding' and can overlap with neighboring tiles. A small padding means less overlap, and faster processing. But if your objects are too big, they might disappear or become clipped across tiles. If that happens, try increasing this value.
* **Input channels**: Select which channels to input. Some models take a fixed number, others (thanks to ChannelNet) don't care. You can even apply color deconvolution here, if you want an easy way to apply a fluorescence model to a brightfield image.
* **Outputs**: Select what to output. Some models just have one output (e.g. nuclei or cells). Others have multiple outputs, and you can select any combination.
* **Make measurements**: Optionally add QuPath measurements to whatever InstanSeg detected.
* **Random colors**: Optionally set the color of each detected object randomly. This helps distinguish neighboring objects.

## How do I run this across multiple images?
The extension is scriptable - the core parameters are logged in the history, and can be converted into a script.

See [Workflows to scripts](https://qupath.readthedocs.io/en/stable/docs/scripting/workflows_to_scripts.html) in the docs for more details.

Or modify the example script below:
```groovy
qupath.ext.instanseg.core.InstanSeg.builder()
    .modelPath("/path/to/some/model")
    .device("mps")
    .nThreads(4)
    .tileDims(512)
    .interTilePadding(32)
    .inputChannels([ColorTransforms.createChannelExtractor("Red"), ColorTransforms.createChannelExtractor("Green"), ColorTransforms.createChannelExtractor("Blue")])
    .outputChannels()
    .makeMeasurements(true)
    .randomColors(false)
    .build()
    .detectObjects()
```

## How do I cite this?
If you use this extension in any published work, we ask you to please cite
1. At least one of the two InstanSeg preprints above (whichever is most relevant)
2. The main QuPath paper - details [here](https://qupath.readthedocs.io/en/stable/docs/intro/citing.html)




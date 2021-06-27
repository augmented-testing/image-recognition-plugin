# JMScout

JMScout brings image recognition features known from Visual GUI Testing (VGT) to Scout and combines it with Scout's Augmented Testing (AT) approach.

## Context

Automated Graphical User Interface (GUI) tests have been known to be fragile in the past, thus requiring a lot of maintenance. Document Object Model (DOM) tools or other component based tools solves fragility to an extent, but also has its drawbacks. Accessing components requires knowledge of widget metadata and in a lot of cases this is not possible due to programmatic limitations. A somewhat novel approach to this problem is VGT. Instead of components VGT uses image recognition as its driver. VGT also suffers from fragility issues but its main benefit include being system agnostic. There are VGT tools available that use scripts, these scripts has been shown to be more fragile than some DOM-based models and require more maintenance, but has potential to improve in time- and cost efficiency when creating the base test cases to offset this downside.

JMScout is a proof-of-concept plugin for Scout to improve time efficiency in test development by combining VGT with AT.  

The main repository for Scout can be found [here](https://github.com/augmented-testing/scout).

## Requirements

JMScout and Scout are developed in Java and therefore require the Java Runtime Environment (JRE).
JMScout was developed and tested with Java version 8.

## To install this plugin in Scout

1) Compile the `ImageRecognition.java` file into .class files.
   - NOTE: The jar files in /lib/ are dependencies.
2) Place the `ImageRecognition.class` files in the Scout /plugin/ directory.
3) Place the jar files in this packs /lib/ in the Scout /bin/selenium/ folder.
4) Start Scout
5) In Scouts Plugin menu, disable SeleniumPlugin and enable ImageRecognition before loading a project.
6) The plugin should now work and be active once a project is loaded.

## Good to know

CTRL + K is the key rebinding window, which details what buttons to use for what functionality.
ALL keybindings require the CTRL key to be held.

CTRL + R is used to automatically run a test suite from the step you are currently on.
You can click on any widget with a blue outline to perform the widget.

## License

Copyright 2021 Joel Amundberg and Martin Moberg

This work is licensed under [Apache 2.0](./LICENSES/Apache2.0-jmscout.md).

See the [NOTICE.TXT](./NOTICE.TXT) file and the [LICENSES](./LICENSES/) folder in the root of this project for license details.

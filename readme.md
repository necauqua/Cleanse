# Cleanse

[![Downloads Number](http://cf.way2muchnoise.eu/short_cleanse.svg?badge_style=flat)](https://www.curseforge.com/minecraft/mc-mods/cleanse)
![GitHub Workflow Status](https://img.shields.io/github/workflow/status/necauqua/cleanse/Release?style=flat-square)
<br>
[![Latest Release](https://img.shields.io/github/release/necauqua/cleanse.svg?label=last%20release&style=flat-square)](https://www.curseforge.com/minecraft/mc-mods/cleanse/files)

#### Remove the wall of text spammed into your chat on login!

This mod simply disables the part of the game code responsible for adding new chat lines of the client for one second (configurable) after the world was opened.

This is its only function, and it helps to remove the 'We have an update, and we don't care that Forge had a built-in notification system for this for ages', 'We are compatible with this in-game wiki, so we think that you MUST know about that', 'Here are my socials (pure cringe)' and other dumb chat spam on login.

NOTE: If some mod author is extra cringe, they can (easily) deliberately work around what this mod does and do their chat spam anyway, nothing you can do about it, but hopefully nobody lacks *that* much self-esteem.

IF YOU STILL SEE PARTIAL SPAM: well first look at the latest log, if the `[Cleanse]: Reenabled adding new chat lines` just happens before something sends something in chat, then either you have an enormous modpack, or the mod sends the message with a delay - only thing you can do is increase the time in the config

## License
The mod is released under the terms of the MIT license, which means that to use it you must include the license notice file (it has my name on top of it). This file is present in the jar file, so you don't have to do anything and can freely use it *in however way you want*, including adding it to your modpacks.

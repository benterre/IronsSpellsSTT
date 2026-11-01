# AI development and Codex prompt history

Maintainer and mod author: **benterre**. This mod was created using **GPT-6.1 Sol (Extra High)** inside Codex, as specified by the maintainer.

## AI development disclosure

AI assistance was substantial and included implementation, debugging, tests, compatibility research, documentation and release preparation. benterre supplied the requirements, recordings, gameplay feedback and development decisions. This disclosure is separate from Git authorship; no AI co-author is requested for the repository.

The shipped recognizer uses local acoustic templates and signal processing. It does not contact a generative AI service, download a speech model or require an external program.

For platform submissions, disclose AI-generated code and text. Disclosure alone does not guarantee approval under [Modrinth's AI policy](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai).

## About this prompt record

The following are selected original project prompts, including their typos, with personal file paths removed and Markdown formatting normalized. This is not a complete transcript of every follow-up. Historical requests may be superseded by the maintainer's clarifications below.

## First prompt

> # An addon mod for iron's Spells 'n Spellbooks: STS
> You are a senior java developer, specialized in minecraft mods. You will work on Neoforge for minecraft v1.21.1.
>
> You are tasked with developing a fast, light-weight, Speech-to-spell mod which turns players words into actionable spell casting within the Iron's Spells 'n Spellbooks mod.
>
> **Requirements**: as this mod ties with the spell casting system of Iron's Spells 'n Spellbooks, that mod is a mandatory dependency.
>
> **Compatibility**: the mod should be compatible with the `simple voice chat` mod. All audio should be rerouted via that mod, so that, when available, we can apply voice modifiers to the player. The mod should also be compatible with any other mod which adds spells that use Iron's spell casting system (eg `Apex Arcana`, `GTBC's Spellbooks`, `Spells Gone Wrong`, etc).
>
> **Easy to deploy**: the mod should easily interface with the Iron's Spells spell casting system, such that any other mod which happens to use it can automatically make use of STT to cast spells. Actually. All spell that use that system should be castable via our mod, making integration with addon mods automatic. Much better.
>
> **Light weight and Fast**: This is the main goal of this task. Many STT mods exist, which interface with Iron's Spells, however, these use large STT model like whisper. This is not ideal. We should develop a method to recognize the players words with minimal latency and minimal impact on memory usage. More on this below.
>
> ## Phase 1: prepare the workspace
> You will find that we already worked on multiple similar projects in neighboring folders. You can copy the fundamental files, needed to setup the working environment in the current directory.
>
> You should also download `simple voice chat`, `Iron's Spells 'n Spellbooks` and may of its addon mods for later testing.
>
> ## Phase 2: finding the best STT method
> This phase can be handled in parallel by another agent. The task is simple, audit all possible methods for recognizing the players speech and turn it into actionable spells.
>
> You should find many possible ways of doing this, some of which are available online, some others which you will build from scratch, specifically for this mod.
>
> Here are a few ideas to start with:
> - whisper runs in the background -> words are recognized and sent to the mod -> the mod identifies a spell from the list of words -> casts the corresponding spell. This is not idea! whisper uses a lot of compute, and a lot of memory. Its processing cost translates into increased latency. Recognizing standard English words might make it impossible to cast spells that are not standard words (eg. Ignis)
> - other STT methods: same problem as above
> - (promising) a non STT voice pattern recognition. Here is my idea. You need to audit it against your own ideas and decide if this is a good path. Since many spells have unique pronunciation, those will not come out from a STT software. Furthermore, different players may pronounce spells differently. Here I suggest having each player record 10 runs of them pronouncing the spell (more on this below). The mod will then locally save a unique signature for how a given player pronounces a spell. Then, during gameplay, we can perform a fast lookup for this signature from the player's speech. If we can make this fast, it should fix all the problems STT have.
> - Other ideas you can think of.
>
> **My idea in more detail**: (only follow this if my idea is the most promising). When a user holds a spell in their hand, or hovers above it with their mouse, they can press the mod's shortcut. This should open a interface, resembling a Grimoire, which shows all the spells the user has unlocked. If accessed after hovering above a given spell, it opens that spell's page directly. For each spell the user can select it and see a waveform for all 10 of their recordings. They can then delete recordings and/or add a new one for that spell.
>
> This Grimoire system is the main and only GUI for our mod. This shortcut should be assignable in the shortcuts part of the settings.
>
> **IMPORTANT**: this mod must work without the need for an external program to be running. Everything should be done via minecraft and its java runtime. Use the most appropriate language/wrapper for this task. You should audit multiple options: native JAVA, C with wrapper, Rust with wrapper, etc. Find the fastest implementation which uses the smallest amount of memory.
>
> And the end of this task, you should produce a markdown file with a table listing the various options you went through, their CPU cost, and memory cost. From that, you should highlight the best option.
>
> ## Phase 3: connecting the speech system with the rest of the mod
> Enter this phase only when the most efficient and fastest speech recognition system has been decided in Phase 2. You should connect up the speech part of the mod with the rest of the java code.
>
> Importantly, this mod should work without the need of external scripts running, or any external program. In other words, the user should just be able to drag and drop the jar into their favorite modpack that contains Iron's Spells and/or Simple Voice Chat and other addon mods, and play.
>
> The mod should be server-compatible. You should test server runtimes together with Simple Voice Chat, to make sure that players voices are sent through the voice chat and to the mod at the same time. For future phases, you should also test modifying the players voices through Simple Voice Chat (pitch change, etc). This will be implemented when casting special spells later.
>
> At this stage, you can ask me to record voice lines for various spells so that you can actually test spell casting in more realistic situations. You can give me a list of 10 spells for which I will record the voices and drop the files into your desired location. Just tell me what to do.
>
> Start this project and return when you have completed all three phases. Ask me questions at any time if the instructions are not fully clear.

## Follow-up prompt

> That is great! I tested the mod, and can confirm that it works great. Testing with third parties is still pending. We can now move to the next phase, and get the mod feature complete.
> ## Phase 4: Grimoire-less casting and options.toml
> Now that we have established the core gameplay, we can add more features to the mod.
>
> The first is an option to be able to cast any spell that the player has recorded before, even if that spell is not equipped in the spellbook. This is how it should work. The player unlocks the spell by having the scroll in their hand, but they do not equip it. The player still records audio for that spell. They then equip their spellbook and say the name of that unequipped spell. What the mod should do then is cast said spell at the minimal level (1) and cost the player 50% mana to do so. So even if their scroll for that spell is level 5, the Grimoire-less cast is only level 1, and costs 50% more mana than the level 1 spell would cost if it were in the Grimoire.
>
> Another optional feature is to be able to cast any "learnt spell" at the highest unlocked level, if the player is holding a magical staff or wand (of any item that can cast spell using right click). Here, the mod should keep track of every spell the player unlocks (already the case) but should also keep track of the level of that spell. If a player unlocks a spell, say Fireball at level 1, and then decides to upgrade the spell using ink, the mod should keep track of that. The player doesn't need to hold said upgrade scroll in their hand, as long as it sat in their inventory at some point, the mod should be able to update the new maximal level. Casting without a Grimoire would then cost the player 50% mana than their unlocked maximum spell level, and the spell would be casted at that level if enough mana is available.
>
> The final optional feature is the ability to cast without needing to equip a Grimoire, or have a magic item in hand. Here we have again two options, casts are level 1, or casts are max unlocked level, depending on the choice set in the config file.
>
> As mentioned above, all these options should sit in a single config file:
> - Grimoireless-cast any spell (default: true): cast any learnt spell at level 1
> - Grimoireless-cast at max level (default: false): cast any learnt spell at the maximum learnt level
> - cast without wand (default: false): can voice cast without a Grimoire, or magical staff
>
> **Additional goal**: The mod currently only supports a specific version of Simple Voice Chat, and Iron's Spells. We need to test compatibility with all available versions of these mods, and allow the mod to work for any of those. The audio pipeline should work the same way even with those older versions of Simple Voice Chat, so I don't doubt that making the mod compatible for those versions should be easy. Make the mod compatible with as many versions of Iron's Spells and Simple Voice Chat as possible (still in 1.21.1).
>
> Report back when all instructions are complete.

## Follow-up prompt

> Thank you, that is great. Before we move on to phase 5, there is a small bug that needs fixing.
>
> Currently, clicking the hotkey (G) while in game, or in the inventory, opens the Grimoire, as expected. However, this hotkey detection is too strong. Indeed, while typing something in the creative inventory search bar, pressing G triggered the Grimoire to open. This happened even though I already was typing something.
>
> The Grimoire must only trigger when the player is in gameplay, or has their mouse over an item in their inventory, and not when they are typing something.
> ## Phase 5: voice effects for special spells
> In this phase of the project, we will implement a system whereby the player's voice will be played out loud with various effects when casting a strong spell.
>
> Since our mod doesn't block the audio pipeline, we cannot stop the original voice from coming through. However, once a strong spell voice cast triggers, we should replay that audio in a 50 block radius from the player to all other players, with pitch change and echo.
>
> The config should add three new options:
> - broadcast strong spells: bool
> - broadcast on max level only: default true (whether to broadcast the players voice with a strong spell only if it is at max level)
> - spells to broadcast: list of spells to broadcast for, eg. Black hole, etc
>
> **Avoid big overheads**: constantly monitoring the audio input to then broadcast the player's spell can cost a lot of CPU resources. We should first audit this method and determine the impact on CPU and RAM. If the impact is quite large, we should set the default option for `broadcast strong spells:` in the options file to false. When that option is set to false, all code linked to this audio saving/broadcasting should be inactive.
>
> When it's set to true, we can apply resource saving methods. For example, depending on the config options, we do not need to save the audio traffic if no strong spells have been learnt. Furthermore, if `broadcast on max level only` is set to True, then we do not need to prepare for audio broadcast if the player doesn't have any max level strong spells in their Grimoire, or have learnt any max level strong spells (if the option to cast at max level Grimoireless is set to true).
>
> **What counts as a strong spell?**: Look at the list of available spells in Iron's Spells, and see which spells require the most mana. Spells like black hole should be counted as strong. Any spell above a certain amount of mana required, and which take a few seconds to fully cast, should then go into that list of strong spells.
>
> **What voice effects exactly?**: strong spells usually take a few seconds to cast. We can use that time to process the audio from the player's voice cast. This audio should be given echo and pitched down a bit. Its levels should be normalized to a loud sound. This should then be played when the spell finally casts (eg. black hole appears). The audio should be heard by all players within a 50 block radius from the player (including the caster).
>
> **Additional effect**: this audio broadcast is supposed to be loud. Hence, it makes sense that creatures such as the Warden can hear it. The broadcast should trigger the Warden's and its skulk sensors' hearing.
>
> Implement and test these new features. Delete all test files when complete. We do not need to keep them if the mod works.
>
> Original prompt text follows; spelling is preserved.


> Before we mark phase 5 as complete, we should fix some aspects of the voice change. I made a mistake when I said "echo". What I actually want is a reverb effect, which also extends the original audio by half a second (let the reverb die out).
>
> Change the echo effect to a reverb. For ten of the recordings inside the incoming folder, apply the whole suite of effects and allow me to listen to them. I will then judge if the effect needs any modifications before proceeding.


## Final prompt

> Thank you very much. Make sure the voice effects default to being off in the config (mark as work in progress).
>
> ## Preparing for publication and universal jars
>
> The mod is now almost feature complete. We can prepare the working directory for publication to github, and consequently for publication to curseforge and modrinth.
>
> Prepare the working directory for this publication: write a README file with a complete description of what the mod does, and how to use it. Include pictures and links to the github, curseforge, modrinth (links to be included after release to those platforms). Mark the voice cloning as wip, and do not include it in the main text in the readme (maybe as a quick mention).
>
> Make sure the mod author is set to my name: `benterre`. Mark unneeded files (test data, etc) in the gitignore.
>
> Do not publish the repo yet.
>
> The git repo will be`benterre/IronsSpellsSTT`. It doesn't exist yet. I will create it when we publish it later.
>
> Modrinth requires us to clearly disclose any use of AI. This will be the case here. He will indicate that the mod was made using GPT-6.1 Sol (Extra High). Prepare a dedicated file in which we can collect the prompts given to Codex.
>
> The license should allow anyone to use the mod as they wish, modify it and redistribute it. What is the best choice for this?
>
> The mod currently works for Neoforge 1.21.1 only. We should extend its compatibility scope before publishing the git. As this mod depends on both Iron's Spells and Simple Voice Chat, we should scan for all versions compatible with both of these (for all modloaders) and make our mod compatible with them. We should not restrict to the latest available version of each mod, for a given version of minecraft, and instead allow it to work for as many versions as possible. Perform the standard client/server tests for all of these and build a universal jar for our mod (one for each mod loader) which works for all miencraft versions.
>
> When the repo is ready and the universal jars and tested and built, return back to me. I will inspect the folder and approve its publication to github. You will then instruct me on how to publish the mod to curseforge and modrinth (I have accounts for both already).

## Packaging clarification

The maintainer subsequently accepted ordinary JARs per Minecraft/loader family, superseding the universal-JAR request:

> Use JARs per Minecraft/loader family (recommended)

## Release version clarification

> Before we publish anything, I noticed that the mod is still marked as 0.4.0-alpha.1. We can now remove the alpha tag, and rebuild the mod as the 0.4.0 release.

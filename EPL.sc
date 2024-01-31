EPL_Voice {
	var <mult, parent, attack;
	var <synth;
	
	*new {|mult, parent, attack|
		^super.newCopyArgs(mult, parent, attack).init;
	}

	init {
		synth = Synth(\EPL_player, [
			\driverIn, parent.driverBus,
			\out, parent.out,
			\mult, mult,
			\buf, parent.buffers[0],
			\freq, parent.periodicityPitch * mult,
			\cutoffMult, 12,
			\bufOffset, parent.bufOffsetBus.asMap,
			\bufOffsetIn, parent.bufOffsetBus,
			\trigModIn, parent.trigModBus,
			\ampControlIn, parent.ampControlBus,
			\ampControlInMc, parent.ampControlBusMc,
			\attack, attack ? parent.attack,
			\release, parent.release,
			\pmAmIn, parent.pmAmBus,
			\rotationSpeedIn, parent.rotationSpeedBus,
			\hpfMultIn, parent.hpfMultBus,
			\hpfRes, parent.hpfRes,
			\pmDetune, parent.pmDetune,
			\masterAmpIn, parent.masterAmpBus,
			\panControlIn, parent.panControlBus
		], parent.multGroup);
	}
}

EPL {
	var <server, <out, <root, <sequence, <numSpeakers, wavetables, target, onLoad, <debug;

	var ratios, <periodicityPitch, phaseOffsets;
	var <distortion;

	var <topGroup, <controlGroup, <multGroup, <distortionGroup;

	var <muteGroups;
	
	var <driverBus, <bufOffsetBus, <trigModBus, <ampControlBus, <ampControlBusMc, <panControlBus;
	var <pmAmBus, <rotationSpeedBus, <hpfMultBus, <detuneBus;
	var <masterAmpBus, <distortionBus;

	var bufOffsetController, pmAmController, hpfMultController, detuneController, ampController, ampControllerMc;
	var panController, trigModControllers;

	var <pmDetune = 0.0, <bufOffsetNew = 0.0;

	var <buffers;
	var detune = 0;
	var <hpfRes = 1;

	var <attack = 0.01, <release = 0.01, <pmAm = 0;

	var driver;
	var <chord;
	var index = 0;

	var transpose = 1;

	*new {| server, out = 0, root = 100, sequence, numSpeakers = 2, wavetables = nil, target, onLoad, debug = false |
		^super.newCopyArgs(
			server,
			out,
			root,
			sequence,
			numSpeakers,
			wavetables,
			target,
			onLoad,
			debug
		).init;
	}

	init {
		if(debug, { "=== EPL.init ===".postln });
		
		server = server ? Server.default;
		chord = Dictionary.new();


		if(sequence.notNil, {
			this.prepareRatios();
		}, {
			"in EPL.init: you need to provide a sequence of Ratio objects!".throw;
		});

		phaseOffsets = this.preparePhaseoffsets();

		server.waitForBoot {
			target = target ? Group.new();

			controlGroup = Group.new(target);
			topGroup = Group.new(controlGroup, \addAfter);
			
			multGroup = Group.new(topGroup, \addAfter);
			distortionGroup = Group.new(multGroup, \addAfter);

			muteGroups = Dictionary.new();

			// Define all the buses
			distortionBus = Bus.audio(server, numSpeakers);
			
			driverBus = Bus.audio(server, numSpeakers);
			bufOffsetBus = Bus.control(server, 1);
			trigModBus = Bus.control(server, numSpeakers);
			ampControlBus = Bus.control(server, 1);
			ampControlBusMc = Bus.control(server, numSpeakers);
			pmAmBus = Bus.control(server, numSpeakers);
			rotationSpeedBus = Bus.control(server, 1);
			detuneBus = Bus.control(server, 1);
			hpfMultBus = Bus.control(server, 1);
			masterAmpBus = Bus.control(server, numSpeakers);
			panControlBus = Bus.control(server, numSpeakers);
		
			bufOffsetBus.set(0);
			trigModBus.set(*0!numSpeakers);
			ampControlBus.set(1);
			ampControlBusMc.set(*1!numSpeakers);
			pmAmBus.set(*0!numSpeakers);
			rotationSpeedBus.set(0);
			hpfMultBus.set(1);
			detuneBus.set(0);
			masterAmpBus.set(*0.8!numSpeakers);
			panControlBus.set(*1.0!numSpeakers);
	
			if(debug, { "loading wavetables...".postln });

			if(debug, {
				"wavetable provided: %".format(wavetables.notNil).postln;
			});

			if(wavetables.notNil, {
				if(wavetables.size < 2, {
					if(debug, {"only one wavetable provided - converting to array for now...".postln; });
					wavetables = wavetables!2;
				});
			}, {
				if(debug, {"no wavetables provided, loading sine wave and sawtooth wave...".postln; });				
				wavetables = [
					Wavetable.sineFill(2048 / 2, [1]),
					Env([1.0,-1.0],[1], 0).asSignal(1024).asWavetable,
				]
			});
			
			server.sync;

			if(debug, { "loading wavetables into buffers...".postln });
			buffers = Buffer.allocConsecutive(2, server, 2048);

			wavetables.do{|w,i| buffers[i].loadCollection(w) };
			// buffers = Buffer.alloc(server, 2048).sine1(1);
			
			if(debug, { "loading synthdefs...".postln });
			this.loadSynthDefs();

			server.sync;

			if(debug, { "loading driver...".postln });
			driver = Synth(\EPL_driver, [
				\freq, periodicityPitch,
				\transpose, transpose,
				\out, driverBus,
				\detuneIn, detuneBus
			], topGroup);
			distortion = Synth(\EPL_distortion, [
				\in, distortionBus,
				\out, out,
				\distOn, 0
			], distortionGroup);

			if(debug, { "driver loaded: %".format(driver.asString).postln; });

			server.sync;

			if(onLoad.isFunction, {
				onLoad.value(this.value());
			});

			"EPL loaded.".postln;
		}
	}

	makeVoice {| mult = 1, attack, release |
		var voice;
		
		if(debug, { "=== EPL.makeVoice ===".postln });

		if(debug, {
			"using mult value of %".format(mult).postln
		});

		if(mult.isInteger.not, {
			"in EPL.makeVoice(): mult % is not integer".format(mult).throw
		});

		if(chord[mult].isNil, {
			voice = EPL_Voice.new(mult, this, attack, release);
			chord[mult] = voice;
		}, {
			voice = chord[mult];
		});

		^voice;
	}

	killVoice {| mult = 1, release |
		if(debug, { "=== EPL.killVoice ===".postln });

		if(debug, {
			"using mult value of %".format(mult).postln
		});

		if(mult.isInteger.not, {
			"in EPL.killVoice(): mult % is not integer".format(mult).throw
		});

		if(chord[mult].notNil, {
			if(debug, {
				"found note with mult %, killing it.".format(mult).postln;
			});

			if(release.notNil, { chord[mult].synth.set(\release, release) });

			chord[mult].synth.set(\gate, 0);
			chord[mult] = nil;
		});
	}

	nextChord { | attack, release |
		var newChord = Dictionary.new();
		var voicesToKill = [];

		sequence[index].do{|ratio|
			var mult = ratio.num;
			newChord[mult] = this.makeVoice(mult, attack, release);
		};

		if(debug, {
			"newChord: %".format(newChord).postln;
			"'old' chord: %".format(chord).postln;
		});
		
		chord.keysValuesDo{|key, value|
			if(newChord[key].isNil, {
				if(debug, {
					"found note from old chord with mult %. killing it.".format(key).postln;
				});
				voicesToKill = voicesToKill.add(key);
			})
		};

		voicesToKill.do{ |n| this.killVoice(n, release) };

		chord = newChord;

		index = (index + 1) % sequence.size;
	}

	goTo {|i, attack, release|
		index = i.clip(0, sequence.size - 1);
		this.nextChord(attack, release);
	}

	stop {|rel = 0.2|
		var voicesToKill = [];

		chord.keysValuesDo{| key, value |
			value.synth.set(\release, rel);
			voicesToKill = voicesToKill.add(key)
		};
		voicesToKill.do{| n | this.killVoice(n) };
	}

	killAll {
		multGroup.set(\gate, 0);
		chord = Dictionary.new();
	}

	playSampleDelay {
		| buffer = 0
		, amp = 1
		, rate = 1
		, factor = 0.001
		, rotate = 0
		, muteGroup
		, sampleOut
		|
		var name = "EPL_SamplePlayerDelays_%ch_rotation%".format(buffer.numChannels, rotate).asSymbol;
		//var name = ("EPL_SamplePlayerDelays_rotation" ++ rotate).asSymbol;
		sampleOut = sampleOut ? out;
		
		if(muteGroup.notNil, {
			if(muteGroups[muteGroup].isNil, {
				muteGroups[muteGroup] = Group.new(multGroup, \addAfter);
			});
			muteGroups[muteGroup].set(\gate, 0);
		});
		
		^Synth(name, [
			\buf, buffer,
			\out, sampleOut,
			\rate, rate,
			\amp, amp,
			\factor, factor,
		], muteGroups[muteGroup] ? multGroup);

	}

	playSampleLooper {
		| buffer = 0
		, amp = 1
		, rate = 1
		, time = 0.1
		, numLoops = 4
		, pan = 0
		, sampleOut
		, panEnd
		, direction = 0
		, muteGroup
		, onStart
		, onEnd
		|

		var name = ("EPL_SamplePlayerLooper_" ++ buffer.numChannels ++ "ch").asSymbol;

		sampleOut = sampleOut ? out;
		
		if(muteGroup.notNil, {
			if(muteGroups[muteGroup].isNil, {
				muteGroups[muteGroup] = Group.new(multGroup, \addAfter);
			});
			muteGroups[muteGroup].set(\gate, 0);
		});
		
		if(numLoops < 1, { numLoops = 1 });

		panEnd = panEnd ? pan;

		if(direction > 0, {
			var pEnd = pan, p = panEnd;
			pan = p;
			panEnd = pEnd;
		});

		fork {
			var synth;
			server.bind {
				synth = Synth(name, [
					\buf, buffer,
					\amp, amp,
					\rate, rate,
					\time, time,
					\panStart, pan,
					\panEnd, panEnd,
					\panTime, time * numLoops,
					\out, sampleOut
				], muteGroups[muteGroup] ? multGroup);

				if(onStart.isFunction, {
					onStart.value();
				});
			};

			wait(time * (numLoops - 1));

			server.bind {
				synth.set(\gate, 0);
				if(onEnd.isFunction, {
					onEnd.value();
				});
			};
		}
	}

	playSample {
		| buffer = 0
		, pan = 0.0
		, amp = 1
		, rate = 1
		, sampleOut
		, muteGroup
		|

		sampleOut = sampleOut ? out;

		if(muteGroup.notNil, {
			if(muteGroups[muteGroup].isNil, {
				muteGroups[muteGroup] = Group.new(multGroup, \addAfter);
			});
			muteGroups[muteGroup].set(\gate, 0);
		});

		^Synth(("EPL_SamplePlayer_" ++ (buffer.numChannels) ++ "ch").asSymbol, [
			\buf, buffer,
			\out, distortionBus,
			\pan, pan,
			\amp, amp,
			\rate, rate,
			\out, sampleOut
		], muteGroups[muteGroup] ? multGroup);
	}

	playClap {|out = 0, amp = 0.7, decay = 0.5, hit_length = 10, multi = false|
		Synth(("EPL_clap_" ++ if(multi, { "multi" }, { "stereo" })).asSymbol, [
			\out, out,
			\amp, amp,
			\hit_length, hit_length,
			\decay, decay
		], multGroup);
	}

	playNoiseBlip {
		| mono=true
		, amp=0.6
		, dur=0.1
		, pan=0
		, clickFreq = 200
		, hiFreq = 9000
		|
		
		var name = ("EPL_noiseblip_" ++ if(mono, { "mono" }, { "multi" })).asSymbol;

		Synth(name, [
			\out, distortionBus,
			\pan, pan,
			\dur, dur,
			\amp, amp,
			\clickFreq, clickFreq,
			\hiFreq, hiFreq
		], multGroup);
	}

	loadSynthDefs {
		if(debug, { "=== EPL.loadSynthdef ===".postln; });
		SynthDef(\EPL_driver, {
			| freq = 20
			, transpose = 1
			, out = 0
			, detuneIn = 0
			|

			var sig, f;
			var detune = In.kr(detuneIn);
			f = freq!numSpeakers * numSpeakers.collect{
				LFNoise1.kr(
					LFNoise1.kr(0.1).range(0.01,0.2)
				).linlin(-1.0,1.0,1-detune,1+detune);
			} * transpose;
			"Driver frequency: %".format(f).postln;
			sig = LFSaw.ar(f).range(0.0,1.0);

			OffsetOut.ar(out, sig);
		}).add;

		SynthDef(\EPL_rotator, {
			| speed = 0.3
			, out = 0
			, gate = 1
			|

			var env = EnvGen.kr(Env.asr(0.1,1,0.1), gate, doneAction:2);
			var sig = LFSaw.kr(speed).range(0.0,1.0);

			Out.kr(out, sig)
		}).add;
		
		SynthDef(\EPL_player, {
			| driverIn = 0
			, out = 0
			, mult = 1
			, gate = 1
			, buf = 0
			, bufOffsetIn = 0
			, attack = 0.01
			, release = 0.01
			, trigModIn = 0
			, ampControlIn = 0
			, ampControlInMc = 0
			, panControlIn = 0
			, pmAmIn = 0
			, hpfMultIn = 0
			, masterAmpIn = 0
			, hpfRes = 1
			, cutoffMult = 12
			, freq = 440
			, lfoModulatorSpeed = 1
			, lfoModulatorAmount = 0
			, rotationSpeedIn = 0
			, rotationSpeed = 3
			, pmDetune = 0.0
			, t_testClickTrig = 0
			|

			var env, sig, driver, phase;
			var ampControl, ampControlMc, panControl, trigMod, modulator, pmAm, hpfMult, bufOffset;
			var masterAmp;
			var rotation;
			var testClick, testClickEnv;

			if(debug, {
				testClickEnv = EnvGen.kr(Env.perc(0,0.01), t_testClickTrig);
				testClick = WhiteNoise.ar(1!numSpeakers) * testClickEnv;
			});

			env = EnvGen.kr(Env.asr(attack, 1, release), gate, doneAction:2);

			driver = In.ar(driverIn, numSpeakers);

			trigMod = K2A.ar(In.kr(trigModIn, numSpeakers));
			ampControl = In.kr(ampControlIn, 1).clip(0.0,1.0);
			ampControlMc = In.kr(ampControlInMc, numSpeakers).clip(0.0,1.0);
			pmAm = In.kr(pmAmIn, numSpeakers);
			hpfMult = In.kr(hpfMultIn, 1);
			bufOffset = In.kr(bufOffsetIn, 1);
			masterAmp = In.kr(masterAmpIn, numSpeakers);
			panControl = In.kr(panControlIn, numSpeakers);

			rotation = LFNoise1.kr(
				rotationSpeed!numSpeakers
			).range(0.0,1.0);
	
			modulator = SinOsc.ar(
				{ Rand(0.0,pmDetune) }!numSpeakers,
				(( driver * mult + phaseOffsets) % 1).linlin(0.0,1.0,-pi,pi)
			);

			phase = (
				(
					driver * mult
					+ trigMod
					+ phaseOffsets
					+ (modulator * pmAm)
					//+ rotation
					//+ lfoModulator
				) % 1
			).linlin(0.0,1.0,-pi,pi);

			sig = VOsc.ar(
				bufpos: buf + bufOffset.clip(0.0,buffers.size - 1.001),
				freq: 0,
				phase: phase
			);
			sig = BLowPass.ar(sig, (freq * bufOffset.linlin(0.0,1.0,1.0,cutoffMult)).clip(20.0,18000));
			sig = BHiPass.ar(
				in: sig,
				freq: (freq * hpfMult.lag(0.01)).clip(20.0,18000),
				rq: hpfRes.lag()
			);
			sig = sig
			* 0.1
			* env
			* ampControl
			* ampControlMc
			* panControl
			* hpfRes.lag().linlin(0.0,1.0,1.0,0.8)
			* masterAmp
			* AmpComp.kr(freq.clip(root / 4, 18000), root / 4, 0.13);

			if(debug, {
				sig = sig + testClick;
			});
			
			OffsetOut.ar(out, sig);

		}).add;

		SynthDef(\EPL_distortion, {
			| in = 0
			, out = 0
			, gain = 1.0
			, distOn = 0
			|

			var sig = In.ar(in, numSpeakers);
			sig = Select.ar(distOn, [
				sig,
				DFM1.ar(sig, 14000, inputgain: gain, noiselevel: 0)
			]);

			Out.ar(out, sig);
		}).add;

		SynthDef(\EPL_controller, {
			| start = 0
			, end = 1
			, dur = 1
			, out = 0
			, curve = 0
			|

			Out.kr(
				out,
				EnvGen.kr(
					Env(
						[start, end],
						[dur],
						curve
					),
					doneAction:2
				)!numSpeakers
				//Line.kr(start, end, dur, doneAction:2)!numSpeakers
			);
		}).add;

		SynthDef(\EPL_controller_mono, {
			| start = 0
			, end = 1
			, dur = 1
			, out = 0
			, curve = 0
			|

			var sig = EnvGen.kr(Env([start, end], [dur], curve), doneAction:2);
			
			Out.kr(out, sig);
		}).add;

		SynthDef(\EPL_3_stage_controller, {
			| start = 0
			, dest1 = 0.5
			, dest2 = 1
			, dur1 = 1
			, dur2 = 1
			, out = 0
			, curve1 = 0
			, curve2 = 0
			|

			Out.kr(
				out,
				EnvGen.kr(
					Env(
						[start, dest1, dest2],
						[dur1, dur2],
						[curve1, curve2]
					),
					doneAction:2
				)!numSpeakers
				//Line.kr(start, end, dur, doneAction:2)!numSpeakers
			);
		}).add;

		SynthDef(\EPL_3_stage_controller_mono, {
			| start = 0
			, dest1 = 0.5
			, dest2 = 1
			, dur1 = 1
			, dur2 = 1
			, out = 0
			, curve1 = 0
			, curve2 = 0
			|

			Out.kr(
				out,
				EnvGen.kr(
					Env(
						[start, dest1, dest2],
						[dur1, dur2],
						[curve1, curve2]
					),
					doneAction:2
				)
				//Line.kr(start, end, dur, doneAction:2)!numSpeakers
			);
		}).add;

		SynthDef(\EPL_TestClick, {
			| out = 0
			, amp = 1
			|

			var env = EnvGen.kr(Env.perc(0,0.01), doneAction:2);
			var sig = WhiteNoise.ar(amp!numSpeakers) * env;

			Out.ar(out, sig);
		}).add;

		2.do{|i|
			var name = ("EPL_noiseblip_" ++ if(i==0, { "mono" }, { "multi" })).asSymbol;
			SynthDef(name, {
				| out = 0
				, amp = 0.4
				, dur = 0.2
				, clickFreq = 200
				, hiFreq = 9000
				, pan = 0.0
				|
				var clickEnv = EnvGen.kr(Env.perc(0,0.04));
				var src = if(i==0, {
					WhiteNoise.ar(1);
				}, {
					WhiteNoise.ar(1!numSpeakers);
				});
				var click = BBandPass.ar(
					src * 3,
					300,
					0.1
				) * clickEnv;
				var env = EnvGen.kr(Env([0,1,1,0],[0.01,dur-0.02,0.01]),doneAction:2);
				var sig = src;
				sig = Mix([
					BBandPass.ar(sig, hiFreq, 0.4),
					BBandPass.ar(sig, hiFreq * 13/9, 0.1);
				]) * -3.dbamp;
				sig = sig + click;
				//	sig = (sig * 3).tanh * 0.3 * amp;
				sig = sig * amp;

				if(i==0, {
					sig = Pan2.ar(sig, pan);
				});

				OffsetOut.ar(out,sig);
			}).add;
		};

		2.do{|i|
			var name = ("EPL_SamplePlayerLooper_" ++ (i+1) ++ "ch").asSymbol;
			SynthDef(name, {
				| time = 0.2
				, out = 0
				, amp = 1
				, panStart = 0
				, panEnd = 0
				, panTime = 1
				, rate = 1
				, gate = 1
				, buf = 0
				|

				var trig = Impulse.kr(1/time * gate);
				var sig = PlayBuf.ar(i+1, buf, rate, trig);

				var pan = Line.kr(panStart, panEnd, panTime);
				
				if(i < 1, {
					sig = sig!2;
				});
				if(numSpeakers < 3, {
					sig = Balance2.ar(sig[0], sig[1], pan);
				}, {
					sig = PanAz.ar(numSpeakers, sig[0], pan);
				});
				sig = sig * amp;
				DetectSilence.ar(sig + gate, doneAction:2);
				Out.ar(out, sig);
			}).add;
		};

		2.do{|i|
			SynthDef(("EPL_SamplePlayer_" ++ (i+1) ++ "ch").asSymbol, {
				| buf = 0
				, out = 0
				, amp = 1
				, pan = 0
				, rate = 1
				, gate = 1
				|

				var env = EnvGen.kr(Env.asr(0, 1, 0.01), gate, doneAction:2);

				var sig = PlayBuf.ar(
					i+1,
					buf,
					rate * BufRateScale.ir(buf),
					doneAction:2
				);

				if(i == 0, {
					sig = sig!2;
				});
				
				if(numSpeakers == 2, {
					sig = Balance2.ar(sig[0], sig[1], pan);
				}, {
					sig = PanAz.ar(numSpeakers, sig[0], pan);
				});

				sig = sig * amp * gate;
				
				OffsetOut.ar(out, sig);
			}).add;
		};
		2.do{|ch|
			ch = ch + 1;
			numSpeakers.do{|i|
				var name = "EPL_SamplePlayerDelays_%ch_rotation%".format(ch, i).asSymbol;
				//var name = ("EPL_SamplePlayerDelays_rotation" ++ i).asSymbol;
				SynthDef(name, {
					| buf = 0
					, out = 0
					, factor = 0.001
					, amp = 1
					, rate = 1
					, gate = 1
					|

					var env = EnvGen.kr(Env.asr(0, 1, 0.01), gate, doneAction:2);
					var sig = PlayBuf.ar(ch,buf,rate * BufRateScale.ir(buf));
					var delays;

					if(ch == 2, {
						sig = sig[0];
					});

					delays = (numSpeakers.div(2)-1).collect{|i|
						i = i + 1;
						DelayC.ar(sig, i * factor, i * factor);
					};

					// ENVELOPE USED ONLY FOR doneAction:2
					EnvGen.kr(Env([0,0],[(factor * (numSpeakers/2)) + 1]),doneAction:2);
					
					sig = numSpeakers.collect{|i|
						if(i < 2, {
							sig
						}, {
							if(i <= numSpeakers.div(2), {
								delays[i-2]
							}, {
								delays[numSpeakers - i - 1]
							})
						})
					}.rotate(i) * amp * env;

					Out.ar(out, sig);
				}).add;
			};
		};

		2.do{|i|
			SynthDef(("EPL_LFO_" ++ if(i>0, {"multi"}, {"mono"})).asSymbol , {
				| out = 0
				, speed = 1
				, range = 1
				, center = 0.5
				|
				var sig = SinOsc.kr(speed).linlin(-1.0,1.0,center-(range/2),center+(range/2));
				if(i>0, { sig = sig!numSpeakers });
				Out.kr(out, sig);
			}).add;
		};

		2.do{|i|
			SynthDef(("EPL_clap_" ++ if(i > 0, { "multi" }, { "stereo" })).asSymbol, {
				| out = 0
				, amp = 1
				, hit_length = 10
				, decay = 0.5
				|

				var src, body, tail, bodyE, tailE;
				var sig;

				src = if(i > 0, {
					WhiteNoise.ar(1!numSpeakers)
				}, {
					WhiteNoise.ar(1);
				});

				bodyE = EnvGen.kr(Env(
					[
						0.dbamp,
						-24.dbamp,
						-6.dbamp,
						-24.dbamp,
						-12.dbamp,
						-24.dbamp,
						-14.dbamp,
						0
					],
					[
						(hit_length - 1) / 1000,
						0.001,
						(hit_length - 1) / 1000,
						0.001,
						(hit_length + 4) / 1000,
						0.001,
						0.210
					],
					-8
				));
				
				body = BHiPass.ar(src, 550, 0.7);
				body = BBandPass.ar(body, 1100, 0.6);
				body = body * bodyE;

				tailE = EnvGen.kr(Env.perc(0, decay, curve: -4), doneAction:2);

				tail = BBandPass.ar(src, 950, 0.7);
				tail = tail * tailE * 0.5;

				sig = body + tail;
				// sig = Compander.ar(
				// 	in: sig,
				// 	control: sig,
				// 	thresh: 0.7,
				// 	slopeBelow: 1.0,
				// 	slopeAbove: 1/3,
				// 	clampTime: 0.080,
				// 	relaxTime: 0.080
				// );

				sig = sig * 1.5 * amp;


				if(i<1, {
					sig = sig!2;
				});
				
				OffsetOut.ar(out, sig);
			}).add;
		};

		if(debug, { "loaded 4 synthdefs".postln });
	}

	createLFO {|destination, speed = 1, range = 1|
		switch(destination, 
			'pmam', {
				pmAmBus.get{| val |
					if(pmAmController.notNil, {
						pmAmController.set(\gate, 0);
						pmAmController = nil;
					});
					pmAmController = Synth(\EPL_LFO_multi, [
						\speed, speed,
						\range, range,
						\center, val,
						\out, pmAmBus
					], controlGroup);
				}
			},
			'bufoffset', {
				bufOffsetBus.get{| val |
					if(bufOffsetController.notNil, {
						bufOffsetController.set(\gate, 0);
						bufOffsetController = nil;
					});
					bufOffsetController = Synth(\EPL_LFO_mono, [
						\speed, speed,
						\range, range,
						\center, val,
						\out, bufOffsetBus
					], controlGroup);
				}
			}
		)
	}

	killLFO {|destination|
		switch(destination,
			'pmam', {
				if(pmAmController.notNil, {
					pmAmController.set(\gate, 0);
					pmAmController = nil;
				});
			},
			'bufoffset', {
				if(bufOffsetController.notNil, {
					bufOffsetController.set(\gate, 0);
					bufOffsetController = nil;
				});
			}
		)
	}

	setAttack { | val |
		attack = val ? attack;
		chord.do { | note | note.synth.set(\attack, attack) };
	}

	setRelease { | val |
		release = val ? release;
		chord.do { | note | note.synth.set(\release, release) };
	}

	setDetune { | val = 0, dur = 0, curve = 0 |
		detuneBus.get{ | old |
			var start = old;

			if(detuneController.notNil, {
				detuneController.free;
				detuneController = nil;
			});

			if(dur == 0, {
				detuneBus.set(val);
			}, {

				detuneController = Synth(\EPL_controller_mono, [
					\start, start,
					\end, val,
					\dur, dur,
					\curve, curve,
					\out, detuneBus
				], controlGroup);
				//			driver.set(\detune, detune);
			});
		};
	}

	setPmDetune {| val |
		pmDetune = val ? pmDetune;
		chord.do { | note | note.synth.set(\pmDetune, pmDetune) };
	}

	setTranspose { | val = 1 |
		transpose = val;
		driver.set(\transpose, transpose);
	}

	setBufOffset { | val = 0, dur = 0 |
		if(dur == 0, {
			bufOffsetBus.set(val);
		}, {
			bufOffsetBus.get{ | old |
				var start = old;

				if(bufOffsetController.notNil, {
					bufOffsetController.free;
					bufOffsetController = nil;
				});
				
				if(dur == 0, {
					bufOffsetBus.set(val);
				}, {
					bufOffsetController = Synth(\EPL_controller, [
						\start, start,
						\end, val,
						\dur, dur,
						\out, bufOffsetBus
					], controlGroup);
				});
			};
		});
	}

	setTrigMod { | val = 0, dur = 0 |
		val = val.asArray;
		if(dur == 0, {
			trigModBus.set(*val);
		}, {
			trigModBus.get{ | old |
				var start = old;
				if(trigModControllers.notNil, {
					trigModControllers.do(_.free);
					trigModControllers = nil;
				});
				
				trigModControllers = start.collect { | startValue, i |
					Synth(\EPL_controller_mono, [
						\start, startValue,
						\end, val.clipAt(i),
						\dur, dur,
						\out, trigModBus.index + i
					], controlGroup);
				};
			}
		});
	}
		
	// bufOffsetBus.get{ | old |
	// 		var start = old;

	// 		if(bufOffsetController.notNil, {
	// 			bufOffsetController.free;
	// 			bufOffsetController = nil;
	// 		});

	// 		if(dur == 0, {
	// 			bufOffsetBus.set(val);
	// 		}, {
				
	// 			bufOffsetController = Synth(\EPL_controller, [
	// 				\start, start,
	// 				\end, val,
	// 				\dur, dur,
	// 				\out, bufOffsetBus
	// 			], controlGroup);
	// 		});
	// 	};
	// }

	setPmAm { | val = 0, dur = 0 |
		pmAmBus.get{ | old |
			var start = old;

			if(pmAmController.notNil, {
				pmAmController.free;
				pmAmController = nil;
			});

			if(dur == 0, {
				pmAmBus.set(*val!numSpeakers);
			}, {				
				pmAmController = Synth(\EPL_controller, [
					\start, start,
					\end, val,
					\dur, dur,
					\out, pmAmBus
				], controlGroup);
			});
		};
	}

	setHpfMult { | val = 1, dur = 0.01 |
		hpfMultBus.get{ | old |
			var start = old;

			if(hpfMultController.notNil, {
				hpfMultController.free;
				hpfMultController = nil;
			});

			hpfMultController = Synth(\EPL_controller, [
				\start, start,
				\end, val,
				\dur, dur,
				\out, hpfMultBus,
				\curve, -4
			], controlGroup)
		}
	}

	setHpfRes { | val = 1 |
		hpfRes = val.clip(0.001,1.0);
		chord.do{|player| player.synth.set(\hpfRes, hpfRes) };
	}

	makeHpfSweep { | peak = 12, dest = 1, decay = 0.3, attack = 0.01 |
		hpfMultBus.get{ | old |
			var start = old;

			if(hpfMultController.notNil, {
				hpfMultController.free;
				hpfMultController = nil;
			});

			hpfMultController = Synth(\EPL_3_stage_controller, [
				\start, start,
				\dest1, peak,
				\dest2, dest,
				\dur1, attack,
				\dur2, decay,
				\out, hpfMultBus,
				\curve2, -4
			], controlGroup);
		}
	}

	makeAmpTrigClean {| peak = 1.0, floor = 0.0, dur = 1 |
		if(ampController.notNil, {
			ampController.free;
			ampController = nil;
		});

		ampControlBus.get{|val|
			ampController = Synth(\EPL_3_stage_controller_mono, [
				\start, val,
				\dest1, peak,
				\dur1, 0.001,
				\dest2, floor,
				\dur2, dur,
				\curve2, -4,
				\out, ampControlBus
			], controlGroup)
		};
	}

	makeAmpTrig {| peak = 1.0, dur = 1, floor = 0.0 |
		if(ampController.notNil, {
			ampController.free;
			ampController = nil;
		});
		
		ampController = Synth(\EPL_controller_mono, [
			\start, peak,
			\end, floor,
			\dur, dur,
			\curve, -4,
			\out, ampControlBus
		], controlGroup);
	}

	makeTrig {| destination, peak = 1.0, floor = 0.0, dur = 1 |
		switch(destination,
			'bufoffset', {
				if(bufOffsetController.notNil, {
					bufOffsetController.free;
					bufOffsetController = nil;
				});

				bufOffsetController = Synth(\EPL_controller_mono, [
					\start, peak,
					\end, floor,
					\dur, dur,
					\out, bufOffsetBus,
					\curve, 0
				], controlGroup);
			},
			'pm', {
				if(pmAmController.notNil, {
					pmAmController.free;
					pmAmController = nil;
				});

				pmAmController = Synth(\EPL_controller, [
					\start, peak,
					\end, floor,
					\dur, dur,
					\out, pmAmBus,
					\curve, 0
				], controlGroup);
			},
			'trigmod', {
				Synth(\EPL_controller, [
					\start, peak,
					\end, floor,
					\dur, dur,
					\out, trigModBus,
					\curve, 0
				], controlGroup)
			}
		);
	}

	fadeIn {|time = 2, curve = 2|
		if(ampControllerMc.notNil, {
			ampControllerMc.do(_.free);
			ampControllerMc = nil;
		});

		ampControllerMc = [];

		ampControlBusMc.get{|val|
			val.do{|value, i|
				ampControllerMc = ampControllerMc.add(
					Synth(\EPL_controller_mono, [
						\start, value,
						\end, 1,
						\dur, time,
						\out, ampControlBusMc.index + i,
						\curve, curve
					], controlGroup)
				);
			}
		}
	}

	fadeOut {|time = 2, curve = -4|
		if(ampControllerMc.notNil, {
			ampControllerMc.do(_.free);
			ampControllerMc = nil;
		});

		ampControllerMc = [];
		
		ampControlBusMc.get{|val|
			val.do{|value,i|
				var bus = ampControlBusMc.index + i;
				ampControllerMc = ampControllerMc.add(
					Synth(\EPL_controller_mono, [
						\start, value,
						\end, 0,
						\dur, time,
						\out, bus,
						\curve, curve
					], controlGroup);
				)
			}
		}
	}

	pan {| pos = 0.0, width = 1.0, dur = 3.0 |
		// `pos' :: value from 0-1, going in a circle.
		//
		// `width' :: value from 0-1, where 0 is just one speaker and
		// 1 is all speakers.

		var mainSpeaker, newWidth, left, right, arr;

		if(panController.notNil, {
			panController.do(_.free);
			panController = nil;
		});

		panController = [];

		width = width * 3;
		if(width >= 3.0, {
			width = inf;
		});
	
		mainSpeaker = ((pos-0.5) * numSpeakers).floor.asInteger;
		newWidth = (width * numSpeakers);
		left = numSpeakers.div(2).collect{|i|
			i = (numSpeakers/2)-1-i;
			i.lincurve(0,newWidth,1,0,-4);
		};
		right = numSpeakers.div(2).collect{|i|

			i.lincurve(0,newWidth,1,0,-4);
		}; 
		arr = left ++ right;
		arr = arr.rotate(mainSpeaker).normalizeSum;

		panControlBus.get{|val|
			val.do{|value,i|
				var bus = panControlBus.index + i;
				panController = panController.add(
					Synth(\EPL_controller_mono, [
						\start, value,
						\end, arr[i],
						\dur, dur,
						\out, bus
					], controlGroup)
				)
			}
		}
	}

	prepareRatios {
		ratios = Ratio.collectionWithCommonDen2(sequence.flatten);
		// TODO

		// I don't understand why the sound produced is one octave too
		// high. Here I am setting the periodicityPitch variable to
		// one octave lower than it should be so that it sounds right,
		// but this is a bad fix.
		periodicityPitch = root / ratios[0].den;

		sequence.do{|rats, i|
			rats = Ratio.collectionWithCommonDen2(rats ++ [Ratio(1,ratios[0].den)]);
			rats.removeAt(rats.size - 1);
			sequence[i] = rats;
		};
	}

	preparePhaseoffsets {
		^numSpeakers.collect{|i|
			(i * numSpeakers.reciprocal) % 1.0;
		}
	}
}
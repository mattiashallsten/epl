EPL_Voice {
	
}

EPL {
	var <server, root, wavetable, target, debug;
	var numSpeakers = 1, out = 0;
	var <buffer;

	var topGroup, controlGroup, multGroup;

	var driverBus;

	var driver;

	*new {| server, root = 100, wavetable, target, debug = false |
		^super.newCopyArgs(server, root, wavetable, target, debug).init;
	}

	init {
		if(debug, { "=== EPL.init ===".postln });
		
		server = server ? Server.default;

		server.waitForBoot {
			target = target ? Group.new();

			topGroup = Group.new(target);
			controlGroup = Group.new(topGroup, \addAfter);
			multGroup = Group.new(controlGroup, \addAfter);

			driverBus = Bus.audio(server, numSpeakers);
			

			if(debug, { "loading wavetable...".postln });
			wavetable = wavetable ? Env([1.0,-1.0],[1], 0).asSignal(1024).asWavetable;

			server.sync;

			if(debug, { "loading wavetable into buffer...".postln });
			buffer = Buffer.alloc(server, 2048).loadCollection(wavetable);

			if(debug, { "loading synthdefs...".postln });
			this.loadSynthDefs();

			server.sync;

			
			if(debug, { "loading driver...".postln });
			driver = Synth(\EPL_driver, [
				\freq, root,
				\out, driverBus
			], topGroup);

			if(debug, { "driver loaded: %".format(driver.asString).postln; });
		}
	}

	makeVoice {| mult = 1 |
		if(debug, { "=== EPL.makeVoice ===".postln });

		if(debug, {
			"using mult value of %".format(mult).postln
		});

		^Synth(\EPL_player, [
			\in, driverBus,
			\out, out,
			\mult, mult,
			\buf, buffer
		], multGroup)
	}

	loadSynthDefs {
		if(debug, { "=== EPL.loadSynthdef ===".postln; });
		SynthDef(\EPL_driver, {
			| freq = 20
			, out = 0
			|

			var sig = Phaser.ar(freq!numSpeakers);

			Out.ar(out, sig);
		}).add;

		SynthDef(\EPL_player, {
			| in = 0
			, out = 0
			, mult = 1
			, buf = 0
			, gate = 1
			|

			var env = EnvGen.kr(Env.asr(), gate, doneAction: 2);
			var phase = (
				(
					In.ar(in, numSpeakers) * mult
				) % 1;
			).linlin(0.0,1.0,-pi,pi);

			var sig = Osc.ar(buf, 0, phase);

			sig = sig * 0.2 * env;

			Out.ar(out, sig);
		}).add;

		if(debug, { "loaded 2 synthdefs".postln });
	}
	
}
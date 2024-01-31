EPL_Sequencer {
	var <epl, server, clock, <destination, <valueSequence, <timeSequence, <legatoSequence;
	var glideTime, onStart, onEach, onLast, onLoop, afterLoad, expand, loopSequence, <data;
	var onNext;

	var numSpeakers;

	var routine;
	var <>index = 0, <counter = 0;

	var executeOnLoopFunction = true, executeOnLastFunction = true;

	var valueSequenceClass, timeSequenceClass;

	*new {
		| epl
		, server
		, clock
		, destination
		, valueSequence
		, timeSequence
		, legatoSequence
		, glideTime = 0
		, onStart
		, onEach
		, onLast
		, onLoop
		, afterLoad
		, expand = false
		, loopSequence = true
		, data
		|

		^super.newCopyArgs(
			epl,
			server,
			clock,
			destination,
			valueSequence,
			timeSequence,
			legatoSequence,
			glideTime,
			onStart,
			onEach,
			onLast,
			onLoop,
			afterLoad,
			expand,
			loopSequence,
			data
		).init;		
	}

	init {
		timeSequence = timeSequence ? [1];
		
		valueSequenceClass = this.checkSequenceClass(valueSequence);
		timeSequenceClass = this.checkSequenceClass(timeSequence);

		this.adjustValueSequenceClass();
		this.adjustTimeSequenceClass();

		data = data ? ();
		
		//timeSequence = timeSequence.asArray;

		if(legatoSequence.notNil, {
			legatoSequence = legatoSequence.asArray;
		});

		if(epl.notNil, {
			numSpeakers = epl.numSpeakers;
		}, {
			numSpeakers = 2;
		});

		if(afterLoad.isFunction, {
			afterLoad.value(this.value());
		});

		// server.waitForBoot {
		// 	this.loadSynthDefs();
		// };
	}

	play {|quant, playOnStartFunc = true|
		this.stop();
		index = 0;

		if(onStart.isFunction && playOnStartFunc, {
			{
				server.bind {
					onStart.value(this.value());
				}
			}.fork(clock, quant);
		});

		this.resume(quant);
	}

	resume {| quant = 8 |
		routine = Routine ({
			
			loop {
				var time = timeSequence.wrapAt(index);
				this.nextStep();
				if(epl.debug, {
					"waiting % clock cycles...".format(time).postln;
				});
				wait(time);
				// Wrap around the larger sequence
				if(loopSequence.not && (index == 0), {
					this.stop();
				});
			}
			
		}).play(clock, quant);

		^this.value();
	}

	stop {
		if(routine.notNil, {
			routine.stop;
			routine = nil;
		});
	}

	nextStep {
		var val;
		var function = { };

		switch(valueSequenceClass,
			'function', {
				if(expand, {
					val = epl.numSpeakers.collect{
						valueSequence.value(this.value());
					};
				}, {
					val = valueSequence.value(this.value());
				});
			},
			'array', {
				val = valueSequence.wrapAt(index);
			},
			'stream', {
				val = valueSequence.next();
			}
		);
		
		switch(destination,
			'pm', {
				//server.bind { epl.setPmAm(val, 0); };
				function = {
					epl.setPmAm(val, glideTime);
					if(epl.debug, {
						"in `EPL_Sequencer.nextStep': set pm amount, logical time: %, physical time: %".format(thisThread.clock.beats, thisThread.clock.elapsedBeats).postln;
					});
				} <> function;
			},
			'bufoffset', {
				//server.bind { epl.setBufOffset(val, 0); };
				function = {
					epl.setBufOffset(val, glideTime);
					if(epl.debug, {
						"in `EPL_Sequencer.nextStep': set bufoffset amount, logical time: %, physical time: %".format(thisThread.clock.beats, thisThread.clock.elapsedBeats).postln;
					});
				} <> function;
			},
			'trigmod', {
				if(val.isArray.not, {
					val = val!epl.numSpeakers;
				});
				//server.bind { epl.trigModBus.set(*val); };
				function = {
					//epl.trigModBus.set(*val);
					epl.setTrigMod(val, glideTime);
					if(epl.debug, {
						"in `EPL_Sequencer.nextStep': set trigmod, logical time: %, physical time: %".format(thisThread.clock.beats, thisThread.clock.elapsedBeats).postln;
					});
				} <> function;
			},
			'detune', {
				//server.bind { epl.setDetune(val) };
				function = { epl.setDetune(val) } <> function;
			},
			'transpose', {
				//server.bind { epl.setTranspose(val); }
				function = { epl.setTranspose(val) } <> function;
			},
			'hpf', {
				//server.bind { epl.setHpfMult(val); }
				function = { epl.setHpfMult(val); } <> function;
			},
			'amptrig', {
				// server.bind {
				// 	if(legatoSequence.notNil, {
				// 		epl.makeAmpTrig(val, legatoSequence.wrapAt(index) * timeSequence.wrapAt(index) / clock.tempo)
				// 	}, {
				// 		epl.makeAmpTrig(val, 0.2)
				// 	})
				// }

				function = {
					if(legatoSequence.notNil, {
						epl.makeAmpTrig(val, legatoSequence.wrapAt(index) * timeSequence.wrapAt(index) / clock.tempo)
					}, {
						epl.makeAmpTrig(val, 0.2)
					})
				} <> function;
			},
			'sample', {
				// server.bind {
				// 	this.playSample(val);
				// }

				function = { epl.playSample(val) } <> function;
			}
		);

		if(onNext.isFunction, {
			function = {
				onNext.value(this.value());
				onNext = nil;
			} <> function;
		});

		// Execute `onLoop' function:
		if((index == 0) && (onLoop.isFunction) && executeOnLoopFunction, {
			// server.bind {
			// 	executeOnLoopFunction = onLoop.value(this.value());
			// }
			function = {
				executeOnLoopFunction = onLoop.value(this.value());
				if(executeOnLoopFunction.isKindOf(Boolean).not, {
					executeOnLoopFunction = false;
				});
			} <> function;
		});

		if(timeSequence.size > valueSequence.size, {
			if((index == (timeSequence.size - 1)) && (onLast.isFunction) && executeOnLastFunction, {
				// server.bind {
				// 	executeOnLastFunction = onLast.value(this.value());
				// }
				function = {
					executeOnLastFunction = onLast.value(this.value());
				} <> function;
			});
		}, {
			if((index == (valueSequence.size - 1)) && (onLast.isFunction) && executeOnLastFunction, {
				// server.bind {
				// 	executeOnLastFunction = onLast.value(this.value());
				// }
				function = {
					executeOnLastFunction = onLast.value(this.value());
					if(executeOnLastFunction.isKindOf(Boolean).not, {
						executeOnLastFunction = false;
					});
				} <> function;
			});
		});

		// Execute `onEach' function
		if(onEach.isFunction, {
			// server.bind {
			// 	onEach.value(this.value())
			// }
			function = {
				onEach.value(this.value())
			} <> function;
		});

		server.bind(function);

		index = index + 1;
		counter = counter + 1;

		// Wrap around the larger sequence
		if(timeSequence.size > valueSequence.size, {
			index = index % timeSequence.size;
		}, {
			index = index % valueSequence.size;
		});
	}

	size {
		if(timeSequence.size > valueSequence.size, {
			^timeSequence.size;
		}, {
			^valueSequence.size;
		});
	}

	destination_ {|val|
		destination = val;
	}

	glideTime_ {|val|
		glideTime = val;
	}

	valueSequence_ {| seq |
		valueSequence = seq;
		valueSequenceClass = this.checkSequenceClass(valueSequence);
		this.adjustValueSequenceClass();
		// if(valueSequence.isFunction.not, {
		// 	valueSequence = valueSequence.asArray;
		// });
	}

	adjustValueSequenceClass {
		if(valueSequenceClass == 'pattern', {
			valueSequence = valueSequence.asStream;
			valueSequenceClass = 'stream';
		});
		if(valueSequenceClass.isNil, {
			valueSequence = valueSequence.asArray;
			valueSequenceClass = 'array';
		});
	}

	timeSequence_ {| seq |
		timeSequence = seq;
		timeSequenceClass = this.checkSequenceClass(timeSequence);
		this.adjustTimeSequenceClass();
		// if(timeSequence.isFunction.not, {
		// 	timeSequence = timeSequence.asArray;
		// });
	}

	adjustTimeSequenceClass {
		if(timeSequenceClass == 'pattern', {
			timeSequence = timeSequence.asStream;
			timeSequenceClass = 'stream';
		});
		if(timeSequenceClass.isNil, {
			timeSequence = timeSequence.asArray;
			timeSequenceClass = 'array';
		});
	}

	checkSequenceClass {| seq |
		^if(seq.isKindOf(Function), {
			'function'
		}, {
			if(seq.isKindOf(Pattern), {
				'pattern'
			}, {
				if(seq.isKindOf(Stream), {
					'stream'
				}, {
					if(seq.isKindOf(Array), {
						'array'
					}, {
						nil
					})
				})
			})
		});
	}

	legatoSequence_ {| seq |
		legatoSequence = seq;
		legatoSequence = legatoSequence.asArray;
	}

	onNext_ {| function |
		onNext = function;
	}

	onLoop_ {| function |
		// Function should return true if function should run every
		// loop, and false if it should run only once.
		onLoop = function;
		executeOnLoopFunction = true;
		// functionRunOnce = runOnce;
		// functionRunCount = 0;
	}

	onLoopPrepend {|function|
		this.onLoop = onLoop <> function;
	}

	onLast_ {| function |
		// Function should return true if function should run every
		// last step, and false if it should run only once.
		onLast = function;
		executeOnLastFunction = true;
	}

	onEach_ {| function |
		onEach = function;
	}

	// playSample {| buffer = 0, pan = 0.0, amp = 1, rate = 1 |
	// 	Synth(("EPL_Sequencer_SamplePlayer_" ++ (buffer.numChannels) ++ "ch").asSymbol, [
	// 		\buf, buffer,
	// 		\out, 0,
	// 		\pan, pan,
	// 		\amp, amp,
	// 		\rate, rate
	// 	]);
	// }

	// loadSynthDefs {
	// 	2.do{|i|
	// 		SynthDef(("EPL_Sequencer_SamplePlayer_" ++ (i+1) ++ "ch").asSymbol, {
	// 			| buf = 0
	// 			, out = 0
	// 			, amp = 1
	// 			, pan = 0
	// 			, rate = 1
	// 			|

	// 			var sig = PlayBuf.ar(i+1, buf, rate, doneAction:2);

	// 			if(i == 0, {
	// 				sig = sig!2;
	// 			});
				
	// 			if(epl.numSpeakers == 2, {
	// 				"epl is stereo -- loading stereo panner...".postln;
	// 				sig = Balance2.ar(sig[0], sig[1], pan);
	// 			}, {
	// 				"epl has % speakers -- loading PanAz...".format(epl.numSpeakers).postln;
	// 				sig = PanAz.ar(epl.numSpeakers, sig[0], pan);
	// 			});
				
	// 			sig = sig * amp;
				
	// 			Out.ar(out, sig);
	// 		}).add;
	// 	};
	// }

	reset {|quant = 8|
		this.stop;
		this.play(quant, false);
	}

	// createEventType {
	// 	Event.removeEventType(\EPL_control);

	// 	Event.addEventType(\EPL_control, {
	// 		if(~epl.notNil, {
	// 			~play = switch(~param,
	// 				'pm', {
	// 					server.bind {
	// 						~epl.setPmAm(
	// 							~val,
	// 							~sustain.value
	// 						)
	// 					}
	// 				},
	// 				'bufoffset', {
	// 					server.bind{
	// 						~epl.setBufOffset(
	// 							~val,
	// 							~sustain.value
	// 						)
	// 					}
	// 				},
	// 				'hpf', {
	// 					server.bind{
	// 						~epl.setHpfMult(
	// 							~val,
	// 							~sustain.value
	// 						)
	// 					}
	// 				}
	// 			)
	// 		}, {
	// 			~play = "You have to provide an instance of EPL!".postln;
	// 		});
			
	// 	}, (param: 'pm', val: 1, dur: 1));
	// }
}
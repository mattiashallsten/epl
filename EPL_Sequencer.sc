EPL_Sequencer {
	var <epl, server, clock, destination, <valueSequence, <timeSequence, <legatoSequence;
	var onStart, onEach, onLast, onLoop, expand;
	var routine;
	var <>index = 0, <counter = 0;
	//var functionRunOnce = false, functionRunCount = 0;
	var executeOnLoop = true, executeOnLast = true;

	*new {
		| epl
		, server
		, clock
		, destination
		, valueSequence
		, timeSequence
		, legatoSequence
		, onStart
		, onEach
		, onLast
		, onLoop
		, expand = false
		|

		^super.newCopyArgs(
			epl,
			server,
			clock,
			destination,
			valueSequence,
			timeSequence,
			legatoSequence,
			onStart,
			onEach,
			onLast,
			onLoop,
			expand
		).init;		
	}

	init {
		if(valueSequence.isFunction.not, {
			valueSequence = valueSequence.asArray;
		});

		timeSequence = timeSequence.asArray;

		if(legatoSequence.notNil, {
			legatoSequence = legatoSequence.asArray;
		});

		server.waitForBoot {
			this.loadSynthDefs();
		};
	}

	play {| quant = 8|
		this.stop();

		if(onStart.isFunction, {
			{
				server.bind {
					onStart.value(this.value());
				}
			}.fork(clock, quant);
		});
		
		routine = Routine ({
			
			loop {
				var time = timeSequence.wrapAt(index);
				this.nextStep();
				if(epl.debug, {
					"waiting % clock cycles...".format(time).postln;
				});
				wait(time);
			}
			
		}).play(clock, quant);
	}

	stop {
		if(routine.notNil, {
			routine.stop;
			routine = nil;
		});
		index = 0;
	}

	nextStep {
		var val;
		var function = { };

		if(valueSequence.isFunction, {
			if(expand, {
				val = epl.numSpeakers.collect{
					valueSequence.value(this.value());
				};
			}, {
				val = valueSequence.value(this.value());
			});
		}, {
			val = valueSequence.wrapAt(index);
		});

		switch(destination,
			'pm', {
				server.bind { epl.setPmAm(val, 0); };
			},
			'bufoffset', {
				server.bind { epl.setBufOffset(val, 0); };
			},
			'trigmod', {
				if(val.isArray.not, {
					val = val!epl.numSpeakers;
				});
				server.bind { epl.trigModBus.set(*val); };
			},
			'detune', {
				server.bind { epl.setDetune(val); }
			},
			'transpose', {
				server.bind { epl.setTranspose(val); }
			},
			'hpf', {
				server.bind { epl.setHpfMult(val); }
			},
			'amptrig', {
				server.bind {
					if(legatoSequence.notNil, {
						epl.makeAmpTrig(val, legatoSequence.wrapAt(index) * timeSequence.wrapAt(index) / clock.tempo)
					}, {
						epl.makeAmpTrig(val, 0.2)
					})
				}
			},
			'sample', {
				server.bind {
					this.playSample(val);
				}
			}
		);

		// Execute `onEach' function
		if(onEach.isFunction, {
			server.bind {
				onEach.value(this.value(), server)
			}
		});

		if(timeSequence.size > valueSequence.size, {
			if((index == (timeSequence.size - 1)) && (onLast.isFunction) && executeOnLast, {
				server.bind {
					executeOnLast = onLast.value(this.value());
				}
			});
		}, {
			if((index == (valueSequence.size - 1)) && (onLast.isFunction) && executeOnLast, {
				server.bind {
					executeOnLast = onLast.value(this.value());
				}
			});
		});

		// Execute `onLoop' function:
		if((index == 0) && (onLoop.isFunction) && executeOnLoop, {
			server.bind {
				executeOnLoop = onLoop.value(this.value());
			}
		});

		index = index + 1;
		counter = counter + 1;

		// Wrap around the larger sequence
		if(timeSequence.size > valueSequence.size, {
			index = index % timeSequence.size;
		}, {
			index = index % valueSequence.size;
		});
	}

	valueSequence_ {| seq |
		valueSequence = seq;
		if(valueSequence.isFunction.not, {
			valueSequence = valueSequence.asArray;
		});
	}

	timeSequence_ {| seq |
		timeSequence = seq;
		if(timeSequence.isFunction.not, {
			timeSequence = timeSequence.asArray;
		});
	}

	legatoSequence_ {| seq |
		legatoSequence = seq;
		legatoSequence = legatoSequence.asArray;
	}

	onLoop_ {| function |
		// Function should return true if function should run every
		// loop, and false if it should run only once.
		onLoop = function;
		executeOnLoop = true;
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
		executeOnLast = true;
	}

	onEach_ {| function |
		onEach = function;
	}

	playSample {| buffer = 0 |
		Synth(("EPL_Sequencer_SamplePlayer_" ++ (buffer.numChannels) ++ "ch").asSymbol, [
				\buf, buffer,
				\out, 0
			]);
	}

	loadSynthDefs {
		2.do{|i|
			SynthDef(("EPL_Sequencer_SamplePlayer_" ++ (i+1) ++ "ch").asSymbol, {
				| buf = 0
				, out = 0
				, amp = 1
				|

				var sig = PlayBuf.ar(i+1, buf, doneAction:2);

				if(i == 0, {
					sig = sig!2;
				});
				
				Out.ar(out, sig);
			}).add;
		};
	}

	reset {|quant = 8|
		{
			index = 0;
			counter = 0;
		}.fork(clock, quant)
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
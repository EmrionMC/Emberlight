package dev.emrion.shaders.mod.runtime;

import dev.emrion.shaders.mod.EmrionShadersClient;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Gives OpenAL Soft's PipeWire thread realtime priority, as PipeWire does for its own clients' audio threads. OpenAL Soft
 * mixes inside that thread's process callback at normal priority; with a shader pack chunk meshing and rendering keep the
 * cores busy, the thread then waits for a core past the 10 ms PipeWire cycle and the stream underruns: crackling.
 * Needs RLIMIT_RTPRIO (realtime group, as for PipeWire itself); otherwise nice -11 if RLIMIT_NICE allows.
 * Measured (shaders on, flying 30 blocks/s): 1700-4200 xruns/min at normal priority, 0 at FIFO 20.
 * Linux only.
 */
public final class AudioPriority {
	private AudioPriority() {}

	private static final int SCHED_FIFO = 1, SCHED_RESET_ON_FORK = 0x40000000;
	/** Low realtime level: above every normal thread, below PipeWire's own data threads (rtkit caps clients at 20 too). */
	private static final int RT_PRIORITY = 20;
	private static final MethodHandle SCHED, NICE;

	static {
		MethodHandle sched = null, nice = null;
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
			try {
				Linker linker = Linker.nativeLinker();
				var lookup = linker.defaultLookup();
				sched = linker.downcallHandle(lookup.find("sched_setscheduler").orElseThrow(),
						FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
				nice = linker.downcallHandle(lookup.find("setpriority").orElseThrow(),
						FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
			} catch (Throwable e) {
				EmrionShadersClient.LOG.warn("Audio thread priority unavailable", e);
				sched = nice = null;
			}
		}
		SCHED = sched;
		NICE = nice;
	}

	/** After OpenAL opened the device (Library.init): its PipeWire thread ("ALSoftP0", per device) exists now. */
	public static void raise() {
		if (SCHED == null) return;
		try (DirectoryStream<Path> tasks = Files.newDirectoryStream(Path.of("/proc/self/task")); Arena arena = Arena.ofConfined()) {
			var param = arena.allocate(ValueLayout.JAVA_INT);
			param.set(ValueLayout.JAVA_INT, 0, RT_PRIORITY);
			for (Path task : tasks) {
				String name;
				try {
					name = Files.readString(task.resolve("comm")).strip();
				} catch (java.io.IOException gone) {
					continue; // thread ended meanwhile
				}
				if (!name.startsWith("ALSoftP")) continue;
				int tid = Integer.parseInt(task.getFileName().toString());
				if ((int) SCHED.invokeExact(tid, SCHED_FIFO | SCHED_RESET_ON_FORK, param) == 0) {
					EmrionShadersClient.LOG.info("Audio thread {} at realtime priority {}", name, RT_PRIORITY);
				} else {
					// no realtime permission: nice -11 needs RLIMIT_NICE 31 (PRIO_PROCESS on a tid: that thread only)
					boolean niced = (int) NICE.invokeExact(0, tid, -11) == 0;
					EmrionShadersClient.LOG.info("Audio thread {}: no realtime priority{}", name, niced ? ", nice -11" : " or nice allowance");
				}
			}
		} catch (Throwable e) {
			EmrionShadersClient.LOG.warn("Cannot raise the audio thread priority", e);
		}
	}
}

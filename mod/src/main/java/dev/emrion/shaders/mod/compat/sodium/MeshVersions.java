package dev.emrion.shaders.mod.compat.sodium;

/** A Sodium SectionRenderDataStorage's change counters (SectionRenderDataStorageMixin), read by SodiumRt. Render thread. */
public interface MeshVersions {
	/** Per local section: bumped on every vertex upload (a new mesh may reuse the old BufferSegment object). */
	int[] emrion$uploads();

	/** Bumped on any vertex upload, removal or move (defragmentation, buffer resize). */
	int emrion$version();
}

package dev.emrion.shaders.mod.vertex;

/**
 * Per-draw identity for iris_Entity (Iris CapturedRenderingState). MC 26.3 submits features first and builds their vertices
 * later, sorted by render type, so the ids are captured into each submit record when it is created ({@link Holder}) and
 * restored from it while the feature renderers write its vertices. Render thread only.
 *
 * <p>Packed as three unsigned 16-bit values (entity | blockEntity &lt;&lt; 16 | item &lt;&lt; 32), the in-memory layout of the
 * RGBA16_UINT attribute; −1 (unmapped) becomes 65535 as with Iris's USHORT attribute, 0 = none.
 */
public final class EntityIds {
	private EntityIds() {}

	/** Ids of the entity / block entity / item being submitted, or of the submit whose vertices are being written. */
	public static int entity, blockEntity, item;
	/** True while the world or hand feature frame is prepared with a pack active: ENTITY draws use the extended format. */
	public static boolean extending;
	/** True while the vertices of a submit made by a block entity are written (bit 48 of a Holder's ids). */
	public static boolean fromBlockEntity;
	/** Holder-only flag above the three ids: the submit was made by a block entity. */
	public static final long BLOCK_ENTITY_SUBMIT = 1L << 48;

	public static long packed() {
		return (entity & 0xFFFFL) | (blockEntity & 0xFFFFL) << 16 | (item & 0xFFFFL) << 32;
	}

	public static void restore(long packed) {
		entity = (short) packed;
		blockEntity = (short) (packed >>> 16);
		item = (short) (packed >>> 32);
		fromBlockEntity = (packed & BLOCK_ENTITY_SUBMIT) != 0;
	}

	/** Implemented (via mixin) by the submit records whose vertices may use the entity format. */
	public interface Holder {
		long emrion$ids();
	}

	/** Implemented (via mixin) by ItemStackRenderState: the item id resolved when the stack's layers were built. */
	public interface ItemHolder {
		void emrion$setItem(int id, boolean blockItem);
	}
}

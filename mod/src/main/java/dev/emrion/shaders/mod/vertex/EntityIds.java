package dev.emrion.shaders.mod.vertex;

/**
 * Per-draw iris_Entity ids, captured per submit record because MC 26.3 builds vertices later, sorted by render type. Render thread only.
 * Packed as three u16 (RGBA16_UINT layout); -1 becomes 65535 as in Iris.
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
	/** True while the camera entity (the local player) or what it carries is submitted or its vertices are written (bit 49 of a Holder's ids). */
	public static boolean localPlayer;
	public static final long LOCAL_PLAYER_SUBMIT = 1L << 49;
	/** Flag in the vertex's iris_Entity w (above the ObjectMotion slot): written by the local player (RtEntities tags its instances). */
	public static final int LOCAL_PLAYER_W = 0x8000;

	public static long packed() {
		return (entity & 0xFFFFL) | (blockEntity & 0xFFFFL) << 16 | (item & 0xFFFFL) << 32;
	}

	public static void restore(long packed) {
		entity = (short) packed;
		blockEntity = (short) (packed >>> 16);
		item = (short) (packed >>> 32);
		fromBlockEntity = (packed & BLOCK_ENTITY_SUBMIT) != 0;
		localPlayer = (packed & LOCAL_PLAYER_SUBMIT) != 0;
	}

	/** Implemented (via mixin) by the submit records whose vertices may use the entity format. */
	public interface Holder {
		long emrion$ids();

		/** Object motion key of the submit (ObjectMotion), 0 = untracked. */
		long emrion$motion();
	}

	/** Implemented (via mixin) by ItemStackRenderState: the item id resolved when the stack's layers were built. */
	public interface ItemHolder {
		void emrion$setItem(int id, boolean blockItem);
	}
}

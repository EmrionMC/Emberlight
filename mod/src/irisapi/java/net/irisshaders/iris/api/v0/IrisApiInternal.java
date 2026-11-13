package net.irisshaders.iris.api.v0;

import org.jetbrains.annotations.ApiStatus;

/*
 * Modified by Emrion Shaders (2026): this copy of the Iris API (LGPL-3.0-only, https://github.com/IrisShaders/Iris) is
 * put on the class path only when Iris is NOT installed, so the implementation is Emrion Shaders' instead of
 * net.irisshaders.iris.apiimpl.IrisApiV0Impl. All other files of this package are unmodified apart from the 26.3
 * class names of RenderPipeline and VertexFormat.
 */
@ApiStatus.Internal
public class IrisApiInternal {
	static final IrisApi INSTANCE;

	static {
		try {
			INSTANCE = (IrisApi) Class.forName("dev.emrion.shaders.mod.compat.EmrionIrisApi").getField("INSTANCE").get(null);
		} catch (IllegalAccessException | NoSuchFieldException | ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}
}

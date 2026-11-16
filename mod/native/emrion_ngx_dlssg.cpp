/*
 * SPDX-License-Identifier: MIT (mod/native/LICENSE)
 * Emberlight DLSS Frame Generation shim, the frame-generation half of emrion_ngx.c (NVIDIA's DLSS-G parameter header
 * needs C++). Our code; the compiled binary is under LICENSE-DLSS.txt (see emrion_ngx.c).
 */
#include <string.h>
#include <vulkan/vulkan.h>
#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers_vk.h"
#include "nvsdk_ngx_helpers_dlssg_vk.h"

#ifdef _WIN32
#define EXPORT extern "C" __declspec(dllexport)
#else
#define EXPORT extern "C" __attribute__((visibility("default")))
#endif

extern "C" NVSDK_NGX_Parameter *emrion_ngx_params(void);

/* 1 = DLSS FG available; 0 = not (needsDriver set when a newer driver is required); negative = NGX not initialised. */
EXPORT int emrion_ngx_dlssg_available(int *needsDriver) {
	NVSDK_NGX_Parameter *params = emrion_ngx_params();
	if (!params) return -1;
	int available = 0, needs = 0;
	NVSDK_NGX_Parameter_GetI(params, NVSDK_NGX_Parameter_FrameGeneration_Available, &available);
	NVSDK_NGX_Parameter_GetI(params, NVSDK_NGX_Parameter_FrameGeneration_NeedsUpdatedDriver, &needs);
	*needsDriver = needs;
	return available;
}

EXPORT int emrion_ngx_dlssg_create(VkCommandBuffer cb, unsigned dw, unsigned dh, unsigned rw, unsigned rh, int backbufferFormat, void **handle) {
	NVSDK_NGX_DLSSG_Create_Params create;
	memset(&create, 0, sizeof create);
	create.Width = dw;
	create.Height = dh;
	create.NativeBackbufferFormat = (unsigned) backbufferFormat;
	create.RenderWidth = rw;
	create.RenderHeight = rh;
	return (int) NGX_VK_CREATE_DLSSG(cb, 1, 1, (NVSDK_NGX_Handle **) handle, emrion_ngx_params(), &create);
}

static NVSDK_NGX_Resource_VK resource(const uint64_t *iv, int format, unsigned w, unsigned h, int aspect, bool rw) {
	VkImageSubresourceRange range = {(VkImageAspectFlags) aspect, 0, 1, 0, 1};
	return NVSDK_NGX_Create_ImageView_Resource_VK((VkImageView) iv[1], (VkImage) iv[0], range, (VkFormat) format, w, h, rw);
}

/*
 * images: {backbuffer, hudless, depth, motion, output} x {image, view}; formats likewise. matrices: 4 row-major 4x4
 * (viewToClip, clipToView, clipToPrevClip, prevClipToClip). camera: pos[3], up[3], right[3], fwd[3], near, far, fov, aspect.
 */
EXPORT int emrion_ngx_dlssg_evaluate(VkCommandBuffer cb, void *handle, const uint64_t *images, const int *formats, unsigned rw, unsigned rh,
		unsigned dw, unsigned dh, const float *matrices, const float *camera, float jitterX, float jitterY, float mvScaleX, float mvScaleY,
		int reset) {
	NVSDK_NGX_Resource_VK backbuffer = resource(images + 0, formats[0], dw, dh, VK_IMAGE_ASPECT_COLOR_BIT, false);
	NVSDK_NGX_Resource_VK hudless = resource(images + 2, formats[1], dw, dh, VK_IMAGE_ASPECT_COLOR_BIT, false);
	NVSDK_NGX_Resource_VK depth = resource(images + 4, formats[2], rw, rh, VK_IMAGE_ASPECT_DEPTH_BIT, false);
	NVSDK_NGX_Resource_VK motion = resource(images + 6, formats[3], rw, rh, VK_IMAGE_ASPECT_COLOR_BIT, false);
	NVSDK_NGX_Resource_VK output = resource(images + 8, formats[4], dw, dh, VK_IMAGE_ASPECT_COLOR_BIT, true);
	NVSDK_NGX_VK_DLSSG_Eval_Params eval;
	memset(&eval, 0, sizeof eval);
	eval.pBackbuffer = &backbuffer;
	eval.pHudless = &hudless;
	eval.pDepth = &depth;
	eval.pMVecs = &motion;
	eval.pOutputInterpFrame = &output;
	NVSDK_NGX_DLSSG_Opt_Eval_Params opt;
	memcpy(opt.cameraViewToClip, matrices, 64);
	memcpy(opt.clipToCameraView, matrices + 16, 64);
	memset(opt.clipToLensClip, 0, 64);
	for (int i = 0; i < 4; i++) opt.clipToLensClip[i][i] = 1.0f;
	memcpy(opt.clipToPrevClip, matrices + 32, 64);
	memcpy(opt.prevClipToClip, matrices + 48, 64);
	opt.jitterOffset[0] = jitterX;
	opt.jitterOffset[1] = jitterY;
	opt.mvecScale[0] = mvScaleX;
	opt.mvecScale[1] = mvScaleY;
	opt.cameraPinholeOffset[0] = opt.cameraPinholeOffset[1] = 0.0f;
	memcpy(opt.cameraPos, camera, 12);
	memcpy(opt.cameraUp, camera + 3, 12);
	memcpy(opt.cameraRight, camera + 6, 12);
	memcpy(opt.cameraFwd, camera + 9, 12);
	opt.cameraNear = camera[12];
	opt.cameraFar = camera[13];
	opt.cameraFOV = camera[14];
	opt.cameraAspectRatio = camera[15];
	opt.colorBuffersHDR = false;
	opt.depthInverted = false;
	opt.cameraMotionIncluded = true;
	opt.reset = reset != 0;
	opt.automodeOverrideReset = false;
	opt.notRenderingGameFrames = false;
	opt.orthoProjection = false;
	opt.motionVectorsInvalidValue = 0.0f;
	opt.motionVectorsDilated = false;
	opt.menuDetectionEnabled = false;
	opt.mvecsSubrectSize = {rw, rh};
	opt.depthSubrectSize = {rw, rh};
	opt.hudLessSubrectSize = {dw, dh};
	opt.backbufferSubrectSize = {dw, dh};
	opt.outputInterpSubrectSize = {dw, dh};
	return (int) NGX_VK_EVALUATE_DLSSG(cb, (NVSDK_NGX_Handle *) handle, emrion_ngx_params(), &eval, &opt);
}

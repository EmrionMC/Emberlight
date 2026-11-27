/*
 * SPDX-License-Identifier: MIT (mod/native/LICENSE)
 * Emberlight DLSS shim (our code, no NVIDIA source). It wraps the documented NGX API of NVIDIA's DLSS SDK, whose static
 * library libnvsdk_ngx.a Java cannot load, into a flat C API that the mod calls through Java FFM
 * (dev.emrion.shaders.mod.upscale.Dlss). The compiled shim contains NVIDIA's library, so the binary is under
 * LICENSE-DLSS.txt, not MIT. Built by build-dlss.sh (Linux) or build-dlss.ps1 (Windows).
 */
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <vulkan/vulkan.h>
#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers_vk.h"
#include "nvsdk_ngx_helpers_dlssd_vk.h"

#ifdef _WIN32
#include <windows.h>
#define EXPORT __declspec(dllexport)
static SRWLOCK logLock = SRWLOCK_INIT;
#define LOCK() AcquireSRWLockExclusive(&logLock)
#define UNLOCK() ReleaseSRWLockExclusive(&logLock)
/* the mod passes UTF-8; Windows' mbstowcs would read the ANSI code page */
static bool widen(wchar_t *out, const char *in) {
	return MultiByteToWideChar(CP_UTF8, 0, in, -1, out, 4096) > 0;
}
#else
#include <pthread.h>
#define EXPORT __attribute__((visibility("default")))
static pthread_mutex_t logLock = PTHREAD_MUTEX_INITIALIZER;
#define LOCK() pthread_mutex_lock(&logLock)
#define UNLOCK() pthread_mutex_unlock(&logLock)
static bool widen(wchar_t *out, const char *in) {
	return mbstowcs(out, in, 4095) != (size_t) -1;
}
#endif

static NVSDK_NGX_Parameter *params;
/* a non-random GUID-like project id for NGX's "custom engine" path */
static const char *PROJECT_ID = "6e2d1f0a-4c1b-4f5e-9a57-656d6265726c";

/* NGX's last log lines (its own threads call back too): the mod logs them when init or feature creation fails. */
static char logRing[16][512];
static unsigned logNext;

static void onLog(const char *message, NVSDK_NGX_Logging_Level level, NVSDK_NGX_Feature source) {
	(void) level;
	(void) source;
	LOCK();
	snprintf(logRing[logNext++ % 16], sizeof logRing[0], "%s", message);
	UNLOCK();
}

/* Copies the buffered NGX log lines (oldest first) into buf and clears them. */
EXPORT void emrion_ngx_log(char *buf, unsigned size) {
	size_t n = 0;
	buf[0] = 0;
	LOCK();
	for (unsigned i = logNext > 16 ? logNext - 16 : 0; i < logNext && n + 1 < size; i++) {
		int w = snprintf(buf + n, size - n, "%s%s", logRing[i % 16], strchr(logRing[i % 16], '\n') ? "" : "\n");
		if (w > 0) n += (size_t) w;
	}
	logNext = 0;
	UNLOCK();
}

/* for the frame-generation half (emrion_ngx_dlssg.cpp) */
NVSDK_NGX_Parameter *emrion_ngx_params(void) {
	return params;
}

EXPORT int emrion_ngx_required_extensions(unsigned *instanceCount, const char ***instanceExts, unsigned *deviceCount, const char ***deviceExts) {
	return (int) NVSDK_NGX_VULKAN_RequiredExtensions(instanceCount, instanceExts, deviceCount, deviceExts);
}

/* dataPath: logs; snippetDir: where libnvidia-ngx-dlss.so.* / nvngx_dlss.dll lies (UTF-8). Returns an NVSDK_NGX_Result. */
EXPORT int emrion_ngx_init(VkInstance instance, VkPhysicalDevice pd, VkDevice device, PFN_vkGetInstanceProcAddr gipa,
		PFN_vkGetDeviceProcAddr gdpa, const char *dataPath, const char *snippetDir, int verbose) {
	wchar_t data[4096], dir[4096];
	if (!widen(data, dataPath) || !widen(dir, snippetDir)) return (int) NVSDK_NGX_Result_FAIL_InvalidParameter;
	data[4095] = dir[4095] = 0;
	const wchar_t *paths[1] = {dir};
	NVSDK_NGX_FeatureCommonInfo info;
	memset(&info, 0, sizeof info);
	info.PathListInfo.Path = paths;
	info.PathListInfo.Length = 1;
	info.LoggingInfo.LoggingCallback = onLog;
	info.LoggingInfo.MinimumLoggingLevel = verbose ? NVSDK_NGX_LOGGING_LEVEL_VERBOSE : NVSDK_NGX_LOGGING_LEVEL_ON;
	NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_Init_with_ProjectID(PROJECT_ID, NVSDK_NGX_ENGINE_TYPE_CUSTOM, "0.1", data, instance, pd, device,
			gipa, gdpa, &info, NVSDK_NGX_Version_API);
	if (NVSDK_NGX_FAILED(r)) return (int) r;
	r = NVSDK_NGX_VULKAN_GetCapabilityParameters(&params);
	if (NVSDK_NGX_FAILED(r)) { /* a failure must leave NGX down: the mod only shuts down what init reported as up */
		params = NULL;
		NVSDK_NGX_VULKAN_Shutdown1(device);
	}
	return (int) r;
}

/* 1 = DLSS SR available; 0 = not (needsDriver set when a newer driver is required). Negative = error. */
EXPORT int emrion_ngx_available(int *needsDriver) {
	if (!params) return -1;
	int available = 0, needs = 0;
	NVSDK_NGX_Parameter_GetI(params, NVSDK_NGX_Parameter_SuperSampling_Available, &available);
	NVSDK_NGX_Parameter_GetI(params, NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &needs);
	*needsDriver = needs;
	return available;
}

/* perfQuality: NVSDK_NGX_PerfQuality_Value; flags: NVSDK_NGX_DLSS_Feature_Flags. Records into cb. */
static unsigned dlssPreset; /* NVSDK_NGX_DLSS_Hint_Render_Preset_*, 0 = NGX's default for the title */

/* The render preset (A = 1 ... M = 13) for DLSS SR features created from now on; 0 = NGX's default. */
EXPORT void emrion_ngx_set_preset(unsigned preset) {
	dlssPreset = preset;
}

EXPORT int emrion_ngx_create(VkCommandBuffer cb, unsigned rw, unsigned rh, unsigned dw, unsigned dh, int perfQuality, int flags, void **handle) {
	NVSDK_NGX_DLSS_Create_Params create;
	memset(&create, 0, sizeof create);
	create.Feature.InWidth = rw;
	create.Feature.InHeight = rh;
	create.Feature.InTargetWidth = dw;
	create.Feature.InTargetHeight = dh;
	create.Feature.InPerfQualityValue = (NVSDK_NGX_PerfQuality_Value) perfQuality;
	create.InFeatureCreateFlags = flags;
	/* EMRION_DLSS_PRESET=K|L|M...: overrides the preset for A/B tests. Always set: the parameters outlive a feature */
	const char *env = getenv("EMRION_DLSS_PRESET");
	unsigned v = env && env[0] >= 'A' && env[0] <= 'Z' ? (unsigned) (env[0] - 'A' + 1) : dlssPreset;
	NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, v);
	NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality, v);
	NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced, v);
	NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance, v);
	NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance, v);
	NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraQuality, v);
	return (int) NGX_VULKAN_CREATE_DLSS_EXT(cb, 1, 1, (NVSDK_NGX_Handle **) handle, params, &create);
}

static NVSDK_NGX_Resource_VK resource(VkImage image, VkImageView view, int format, unsigned w, unsigned h, int aspect, bool rw) {
	VkImageSubresourceRange range = {(VkImageAspectFlags) aspect, 0, 1, 0, 1};
	return NVSDK_NGX_Create_ImageView_Resource_VK(view, image, range, (VkFormat) format, w, h, rw);
}

static int evaluate(VkCommandBuffer cb, void *handle, const uint64_t *images, const int *formats, unsigned rw, unsigned rh,
		unsigned dw, unsigned dh, float jitterX, float jitterY, float mvScaleX, float mvScaleY, int reset, float frameMs, int mask) {
	NVSDK_NGX_Resource_VK color = resource((VkImage) images[0], (VkImageView) images[1], formats[0], rw, rh, VK_IMAGE_ASPECT_COLOR_BIT, false);
	NVSDK_NGX_Resource_VK depth = resource((VkImage) images[2], (VkImageView) images[3], formats[1], rw, rh, VK_IMAGE_ASPECT_DEPTH_BIT, false);
	NVSDK_NGX_Resource_VK motion = resource((VkImage) images[4], (VkImageView) images[5], formats[2], rw, rh, VK_IMAGE_ASPECT_COLOR_BIT, false);
	NVSDK_NGX_Resource_VK output = resource((VkImage) images[6], (VkImageView) images[7], formats[3], dw, dh, VK_IMAGE_ASPECT_COLOR_BIT, true);
	NVSDK_NGX_VK_DLSS_Eval_Params eval;
	memset(&eval, 0, sizeof eval);
	eval.Feature.pInColor = &color;
	eval.Feature.pInOutput = &output;
	eval.pInDepth = &depth;
	eval.pInMotionVectors = &motion;
	eval.InJitterOffsetX = jitterX;
	eval.InJitterOffsetY = jitterY;
	eval.InRenderSubrectDimensions.Width = rw;
	eval.InRenderSubrectDimensions.Height = rh;
	eval.InReset = reset;
	eval.InMVScaleX = mvScaleX;
	eval.InMVScaleY = mvScaleY;
	eval.InPreExposure = 1.0f;
	eval.InExposureScale = 1.0f;
	eval.InFrameTimeDeltaInMsec = frameMs;
	NVSDK_NGX_Resource_VK bias;
	if (mask) {
		bias = resource((VkImage) images[8], (VkImageView) images[9], formats[4], rw, rh, VK_IMAGE_ASPECT_COLOR_BIT, false);
		eval.pInBiasCurrentColorMask = &bias;
	}
	return (int) NGX_VULKAN_EVALUATE_DLSS_EXT(cb, (NVSDK_NGX_Handle *) handle, params, &eval);
}

/* images: {color, depth, motion, output} x {image, view}; formats likewise; render and display size. */
EXPORT int emrion_ngx_evaluate(VkCommandBuffer cb, void *handle, const uint64_t *images, const int *formats, unsigned rw, unsigned rh,
		unsigned dw, unsigned dh, float jitterX, float jitterY, float mvScaleX, float mvScaleY, int reset, float frameMs) {
	return evaluate(cb, handle, images, formats, rw, rh, dw, dh, jitterX, jitterY, mvScaleX, mvScaleY, reset, frameMs, 0);
}

/* The same plus a bias-current-colour mask (r channel, render size) as fifth image / format. */
EXPORT int emrion_ngx_evaluate_mask(VkCommandBuffer cb, void *handle, const uint64_t *images, const int *formats, unsigned rw, unsigned rh,
		unsigned dw, unsigned dh, float jitterX, float jitterY, float mvScaleX, float mvScaleY, int reset, float frameMs) {
	return evaluate(cb, handle, images, formats, rw, rh, dw, dh, jitterX, jitterY, mvScaleX, mvScaleY, reset, frameMs, 1);
}

/* ---- DLSS Ray Reconstruction (NGX feature "DLSS-D", model libnvidia-ngx-dlssd.so / nvngx_dlssd.dll) ---- */

/* 1 = DLSS RR available; 0 = not (needsDriver set when a newer driver is required); negative = NGX not initialised. */
EXPORT int emrion_ngx_rr_available(int *needsDriver) {
	if (!params) return -1;
	int available = 0, needs = 0;
	NVSDK_NGX_Parameter_GetI(params, NVSDK_NGX_Parameter_SuperSamplingDenoising_Available, &available);
	NVSDK_NGX_Parameter_GetI(params, NVSDK_NGX_Parameter_SuperSamplingDenoising_NeedsUpdatedDriver, &needs);
	*needsDriver = needs;
	return available;
}

/* Roughness packed into normals.w, hardware (window) depth. Records into cb. */
EXPORT int emrion_ngx_rr_create(VkCommandBuffer cb, unsigned rw, unsigned rh, unsigned dw, unsigned dh, int perfQuality, int flags, void **handle) {
	NVSDK_NGX_DLSSD_Create_Params create;
	memset(&create, 0, sizeof create);
	create.InDenoiseMode = NVSDK_NGX_DLSS_Denoise_Mode_DLUnified;
	create.InRoughnessMode = NVSDK_NGX_DLSS_Roughness_Mode_Packed;
	create.InUseHWDepth = NVSDK_NGX_DLSS_Depth_Type_HW;
	create.InWidth = rw;
	create.InHeight = rh;
	create.InTargetWidth = dw;
	create.InTargetHeight = dh;
	create.InPerfQualityValue = (NVSDK_NGX_PerfQuality_Value) perfQuality;
	create.InFeatureCreateFlags = flags;
	return (int) NGX_VULKAN_CREATE_DLSSD_EXT1(NULL, cb, 1, 1, (NVSDK_NGX_Handle **) handle, params, &create);
}

/*
 * images: {color, depth, motion, output, diffuse albedo, specular albedo, normals (xyz world, w roughness), bias mask,
 * particle mask} x {image, view}; formats likewise (the bias mask is used when mask != 0, the particle mask when its image is
 * not 0). matrices: worldToView, viewToClip, row-major (NGX's
 * left-multiplication convention).
 */
EXPORT int emrion_ngx_rr_evaluate(VkCommandBuffer cb, void *handle, const uint64_t *images, const int *formats, unsigned rw, unsigned rh,
		unsigned dw, unsigned dh, float jitterX, float jitterY, float mvScaleX, float mvScaleY, int reset, float frameMs, int mask, float *matrices) {
	NVSDK_NGX_Resource_VK color = resource((VkImage) images[0], (VkImageView) images[1], formats[0], rw, rh, VK_IMAGE_ASPECT_COLOR_BIT, false);
	NVSDK_NGX_Resource_VK depth = resource((VkImage) images[2], (VkImageView) images[3], formats[1], rw, rh, VK_IMAGE_ASPECT_DEPTH_BIT, false);
	NVSDK_NGX_Resource_VK motion = resource((VkImage) images[4], (VkImageView) images[5], formats[2], rw, rh, VK_IMAGE_ASPECT_COLOR_BIT, false);
	NVSDK_NGX_Resource_VK output = resource((VkImage) images[6], (VkImageView) images[7], formats[3], dw, dh, VK_IMAGE_ASPECT_COLOR_BIT, true);
	NVSDK_NGX_Resource_VK diffuse = resource((VkImage) images[8], (VkImageView) images[9], formats[4], rw, rh, VK_IMAGE_ASPECT_COLOR_BIT, false);
	NVSDK_NGX_Resource_VK specular = resource((VkImage) images[10], (VkImageView) images[11], formats[5], rw, rh, VK_IMAGE_ASPECT_COLOR_BIT, false);
	NVSDK_NGX_Resource_VK normals = resource((VkImage) images[12], (VkImageView) images[13], formats[6], rw, rh, VK_IMAGE_ASPECT_COLOR_BIT, false);
	NVSDK_NGX_VK_DLSSD_Eval_Params eval;
	memset(&eval, 0, sizeof eval);
	eval.pInColor = &color;
	eval.pInOutput = &output;
	eval.pInDepth = &depth;
	eval.pInMotionVectors = &motion;
	eval.pInDiffuseAlbedo = &diffuse;
	eval.pInSpecularAlbedo = &specular;
	eval.pInNormals = &normals;
	eval.pInRoughness = &normals;
	eval.InJitterOffsetX = jitterX;
	eval.InJitterOffsetY = jitterY;
	eval.InRenderSubrectDimensions.Width = rw;
	eval.InRenderSubrectDimensions.Height = rh;
	eval.InReset = reset;
	eval.InMVScaleX = mvScaleX;
	eval.InMVScaleY = mvScaleY;
	eval.InFrameTimeDeltaInMsec = frameMs;
	eval.pInWorldToViewMatrix = matrices;
	eval.pInViewToClipMatrix = matrices + 16;
	NVSDK_NGX_Resource_VK bias;
	if (mask) {
		bias = resource((VkImage) images[14], (VkImageView) images[15], formats[7], rw, rh, VK_IMAGE_ASPECT_COLOR_BIT, false);
		eval.pInBiasCurrentColorMask = &bias;
	}
	NVSDK_NGX_Resource_VK particles;
	if (images[16]) {
		particles = resource((VkImage) images[16], (VkImageView) images[17], formats[8], rw, rh, VK_IMAGE_ASPECT_COLOR_BIT, false);
		eval.pInIsParticleMask = &particles;
	}
	return (int) NGX_VULKAN_EVALUATE_DLSSD_EXT(cb, (NVSDK_NGX_Handle *) handle, params, &eval);
}

EXPORT int emrion_ngx_release(void *handle) {
	return (int) NVSDK_NGX_VULKAN_ReleaseFeature((NVSDK_NGX_Handle *) handle);
}

EXPORT int emrion_ngx_shutdown(VkDevice device) {
	if (params) NVSDK_NGX_VULKAN_DestroyParameters(params);
	params = NULL;
	return (int) NVSDK_NGX_VULKAN_Shutdown1(device);
}

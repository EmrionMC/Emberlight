#ifndef RTX_EXPOSURE
#define RTX_EXPOSURE 1
layout(std430, binding = EL_BUFFER_exposureBuf) buffer ExposureBlock { float exposureValue; float exposureLum; float exposurePad0; float exposurePad1; };
#endif

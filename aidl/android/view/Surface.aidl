// Import-only declaration so IShellService.aidl can reference android.view.Surface.
//
// The aidl tool resolves imports by path under -I, and does not read framework
// parcelables out of android.jar, so a PUBLIC, task-hosting virtual display needs the
// Surface (a SurfaceView's, taken from the app so the display renders into our floating
// window - that is what makes it non-headless). This file is never passed to aidl
// directly, only resolved as an import, so no Java is generated for it.
package android.view;

parcelable Surface;

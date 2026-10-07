#include <jni.h>
#include <android/log.h>
#include <array>
#include <memory>
#define XR_NO_PROTOTYPES
#include <openxr/openxr.h>

namespace {
constexpr int kJoints = XR_HAND_JOINT_COUNT_EXT;
constexpr int kStride = 1 + kJoints * 4;
constexpr int kHeadOffset = kStride * 2;
constexpr int kOutputSize = kHeadOffset + 8;
struct Hands {
    XrInstance instance = XR_NULL_HANDLE;
    XrSpace space = XR_NULL_HANDLE;
    XrSpace viewSpace = XR_NULL_HANDLE;
    std::array<XrHandTrackerEXT, 2> trackers{XR_NULL_HANDLE, XR_NULL_HANDLE};
    PFN_xrDestroySpace destroySpace = nullptr;
    PFN_xrDestroyHandTrackerEXT destroyHand = nullptr;
    PFN_xrLocateHandJointsEXT locate = nullptr;
    PFN_xrLocateSpace locateSpace = nullptr;
    XrResult lastLocateError = XR_SUCCESS;
    ~Hands() {
        for (auto tracker : trackers) if (tracker && destroyHand) destroyHand(tracker);
        if (space && destroySpace) destroySpace(space);
        if (viewSpace && destroySpace) destroySpace(viewSpace);
    }
};
template <typename T> bool resolve(PFN_xrGetInstanceProcAddr get, XrInstance instance, const char* name, T& out) {
    return XR_SUCCEEDED(get(instance, name, reinterpret_cast<PFN_xrVoidFunction*>(&out))) && out;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_casa_archer_metatheatre_NativeHands_nativeOpen(JNIEnv*, jobject, jlong instanceHandle, jlong sessionHandle, jlong procHandle) {
    auto get = reinterpret_cast<PFN_xrGetInstanceProcAddr>(procHandle);
    auto instance = reinterpret_cast<XrInstance>(instanceHandle);
    auto session = reinterpret_cast<XrSession>(sessionHandle);
    if (!get || !instance || !session) return 0;
    auto hands = std::make_unique<Hands>();
    hands->instance = instance;
    PFN_xrCreateHandTrackerEXT createHand = nullptr;
    PFN_xrCreateReferenceSpace createSpace = nullptr;
    if (!resolve(get, instance, "xrCreateHandTrackerEXT", createHand) ||
        !resolve(get, instance, "xrDestroyHandTrackerEXT", hands->destroyHand) ||
        !resolve(get, instance, "xrLocateHandJointsEXT", hands->locate) ||
        !resolve(get, instance, "xrCreateReferenceSpace", createSpace) ||
        !resolve(get, instance, "xrLocateSpace", hands->locateSpace) ||
        !resolve(get, instance, "xrDestroySpace", hands->destroySpace)) {
        __android_log_print(ANDROID_LOG_WARN, "TheatreHands", "hand joints unavailable: extension lookup");
        return 0;
    }
    for (int i = 0; i < 2; ++i) {
        XrHandTrackerCreateInfoEXT info{XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT, nullptr,
            i == 0 ? XR_HAND_LEFT_EXT : XR_HAND_RIGHT_EXT, XR_HAND_JOINT_SET_DEFAULT_EXT};
        auto result = createHand(session, &info, &hands->trackers[i]);
        if (XR_FAILED(result)) {
            __android_log_print(ANDROID_LOG_WARN, "TheatreHands", "hand joints unavailable: create=%d", result);
            return 0;
        }
    }
    for (auto reference : {XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT, XR_REFERENCE_SPACE_TYPE_LOCAL}) {
        XrReferenceSpaceCreateInfo info{XR_TYPE_REFERENCE_SPACE_CREATE_INFO, nullptr, reference,
            {{0, 0, 0, 1}, {0, 0, 0}}};
        if (XR_SUCCEEDED(createSpace(session, &info, &hands->space))) break;
    }
    if (!hands->space) return 0;
    XrReferenceSpaceCreateInfo viewInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO, nullptr, XR_REFERENCE_SPACE_TYPE_VIEW,
        {{0, 0, 0, 1}, {0, 0, 0}}};
    if (XR_FAILED(createSpace(session, &viewInfo, &hands->viewSpace))) return 0;
    __android_log_print(ANDROID_LOG_INFO, "TheatreHands", "hand joints ready");
    return reinterpret_cast<jlong>(hands.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_casa_archer_metatheatre_NativeHands_nativeRead(JNIEnv* env, jobject, jlong handle, jlong predictedDisplayTime, jfloatArray output) {
    auto* hands = reinterpret_cast<Hands*>(handle);
    if (!hands || predictedDisplayTime <= 0 || env->GetArrayLength(output) != kOutputSize) return JNI_FALSE;
    std::array<float, kOutputSize> values{};
    // SpatialInterface.frameTiming publishes the SDK-owned xrWaitFrame prediction.
    const XrTime time = predictedDisplayTime;
    // The SDK can recenter/turn/teleport independently of this LOCAL_FLOOR space.
    // A head pose at the same prediction lets Kotlin align joints to the scene's viewer pose.
    XrSpaceLocation head{};
    head.type = XR_TYPE_SPACE_LOCATION;
    constexpr XrSpaceLocationFlags required = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    if (XR_FAILED(hands->locateSpace(hands->viewSpace, hands->space, time, &head)) ||
        (head.locationFlags & required) != required) return JNI_FALSE;
    values[kHeadOffset] = 1;
    values[kHeadOffset + 1] = head.pose.position.x;
    values[kHeadOffset + 2] = head.pose.position.y;
    values[kHeadOffset + 3] = -head.pose.position.z;
    // Reflecting Z changes quaternion (x,y,z,w) to (-x,-y,z,w).
    values[kHeadOffset + 4] = -head.pose.orientation.x;
    values[kHeadOffset + 5] = -head.pose.orientation.y;
    values[kHeadOffset + 6] = head.pose.orientation.z;
    values[kHeadOffset + 7] = head.pose.orientation.w;
    for (int side = 0; side < 2; ++side) {
        std::array<XrHandJointLocationEXT, kJoints> joints{};
        XrHandJointLocationsEXT locations{XR_TYPE_HAND_JOINT_LOCATIONS_EXT, nullptr, XR_FALSE, kJoints, joints.data()};
        XrHandJointsLocateInfoEXT info{XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT, nullptr, hands->space, time};
        auto result = hands->locate(hands->trackers[side], &info, &locations);
        if (XR_FAILED(result)) {
            if (result != hands->lastLocateError) __android_log_print(ANDROID_LOG_WARN, "TheatreHands", "hand joints locate error=%d", result);
            hands->lastLocateError = result;
            continue;
        }
        hands->lastLocateError = XR_SUCCESS;
        if (!locations.isActive) continue;
        int offset = side * kStride;
        values[offset++] = 1;
        for (const auto& joint : joints) {
            values[offset++] = joint.pose.position.x;
            values[offset++] = joint.pose.position.y;
            // Spatial SDK reflects OpenXR Z into its scene coordinate system.
            values[offset++] = -joint.pose.position.z;
            values[offset++] = static_cast<float>(joint.locationFlags);
        }
    }
    env->SetFloatArrayRegion(output, 0, kOutputSize, values.data());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_casa_archer_metatheatre_NativeHands_nativeClose(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Hands*>(handle);
}

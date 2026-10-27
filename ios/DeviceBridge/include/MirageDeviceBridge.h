#ifndef MIRAGE_DEVICE_BRIDGE_H
#define MIRAGE_DEVICE_BRIDGE_H
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MirageDeviceBridge MirageDeviceBridge;

/* All functions block; call off the UI thread. Status is zero only on success.
   A successful set/clear acknowledges the service, not an observed GPS result.
   Never destroy a handle while another caller is using it. */
MirageDeviceBridge *mirage_device_create(void);
int32_t mirage_device_connect(MirageDeviceBridge *handle, const char *numeric_host,
                             uint16_t lockdown_port, const uint8_t *pairing_bytes,
                             size_t pairing_size);
int32_t mirage_device_set(MirageDeviceBridge *handle, double latitude, double longitude);
int32_t mirage_device_clear(MirageDeviceBridge *handle);
void mirage_device_destroy(MirageDeviceBridge *handle);

/* Fixed, privacy-preserving statuses. Underlying error text is never exported. */
enum {
    MIRAGE_DEVICE_OK = 0,
    MIRAGE_DEVICE_INVALID_ARGUMENT = 1,
    MIRAGE_DEVICE_INVALID_PAIRING = 2,
    MIRAGE_DEVICE_NOT_CONNECTED = 3,
    MIRAGE_DEVICE_CONNECTION_FAILED = 4,
    MIRAGE_DEVICE_WRITE_FAILED = 5,
    MIRAGE_DEVICE_CLEAR_FAILED = 6,
    MIRAGE_DEVICE_TIMED_OUT = 7,
    MIRAGE_DEVICE_ALREADY_CONNECTED = 8,
    MIRAGE_DEVICE_WORKER_UNAVAILABLE = 9
};

#ifdef __cplusplus
}
#endif
#endif

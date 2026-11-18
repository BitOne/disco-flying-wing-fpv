/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * aoa-host: the USB side of a fake SkyController 2, inside the Android
 * emulator. The emulator kernel has dummy_hcd (a virtual USB device
 * controller wired to a virtual host controller) and the Android accessory
 * gadget function, so the emulated phone can be put in USB accessory mode
 * and seen as a USB device by its own host controller. This program plays
 * the SkyController (USB host): it performs the Android Open Accessory
 * handshake as "Parrot" / "Skycontroller 2", claims the accessory interface
 * and relays its bulk endpoints to a mux TCP server (fake-sc2 --mux on the
 * host, reached at 10.0.2.2), so the app takes its real USB path
 * (UsbManager.openAccessory) end to end. When either side closes, it starts
 * over, which is what a real unplug and replug looks like.
 *
 * Needs root (usbfs). usage: aoa-host [--host 10.0.2.2] [--port 4321]
 */
#include <arpa/inet.h>
#include <stdarg.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/usb/ch9.h>
#include <linux/usbdevice_fs.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <unistd.h>

#define AOA_GET_PROTOCOL 51
#define AOA_SEND_STRING  52
#define AOA_START        53
#define GOOGLE_VID       0x18d1
#define BULK_MAX         16384

static const char *const strings[] = {
	"Parrot",           /* manufacturer */
	"Skycontroller 2",  /* model */
	"Fake SkyController 2 (disco-flying-wing-fpv)", /* description */
	"1.0.9",            /* version */
	"https://github.com/BitOne/disco-flying-wing-fpv", /* uri */
	"PI040443FAKESC2",  /* serial */
};

struct link {
	int usb;            /* usbfs device fd */
	int sock;           /* TCP to the mux server */
	unsigned char ep_in, ep_out;
	volatile int stop;
};

static void logf_(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void logf_(const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	vfprintf(stderr, fmt, ap);
	va_end(ap);
	fputc('\n', stderr);
}


/* Finds an accessory-mode device (Google VID, PID 2d00/2d01) and returns
 * its usbfs path in path. */
static int find_device(char *path, size_t size)
{
	DIR *dir = opendir("/sys/bus/usb/devices");
	struct dirent *e;
	int found = 0;
	if (dir == NULL)
		return 0;
	while (!found && (e = readdir(dir)) != NULL) {
		char p[512];
		unsigned vid = 0, pid = 0, bus = 0, dev = 0;
		FILE *f;
		if (strchr(e->d_name, ':') != NULL || e->d_name[0] == '.')
			continue;
		snprintf(p, sizeof(p), "/sys/bus/usb/devices/%s/idVendor", e->d_name);
		if ((f = fopen(p, "r")) == NULL)
			continue;
		if (fscanf(f, "%x", &vid) != 1)
			vid = 0;
		fclose(f);
		snprintf(p, sizeof(p), "/sys/bus/usb/devices/%s/idProduct", e->d_name);
		if ((f = fopen(p, "r")) != NULL) {
			if (fscanf(f, "%x", &pid) != 1)
				pid = 0;
			fclose(f);
		}
		if (vid != GOOGLE_VID || (pid != 0x2d00 && pid != 0x2d01))
			continue;
		snprintf(p, sizeof(p), "/sys/bus/usb/devices/%s/busnum", e->d_name);
		if ((f = fopen(p, "r")) != NULL) {
			if (fscanf(f, "%u", &bus) != 1)
				bus = 0;
			fclose(f);
		}
		snprintf(p, sizeof(p), "/sys/bus/usb/devices/%s/devnum", e->d_name);
		if ((f = fopen(p, "r")) != NULL) {
			if (fscanf(f, "%u", &dev) != 1)
				dev = 0;
			fclose(f);
		}
		if (bus && dev) {
			snprintf(path, size, "/dev/bus/usb/%03u/%03u", bus, dev);
			found = 1;
		}
	}
	closedir(dir);
	return found;
}

#define UDC_PATH "/config/usb_gadget/g1/UDC"
#define UDC_NAME "dummy_udc.0"

/* The phone side of a plug or unplug: binds or unbinds the gadget to the
 * dummy device controller. On a phone, the USB gadget HAL re-enumerates the
 * gadget when the functions change; the emulator's HAL is a stub, so this
 * does it. */
static void udc_bind(int bind)
{
	FILE *f = fopen(UDC_PATH, "w");
	if (f == NULL) {
		logf_("%s: %s", UDC_PATH, strerror(errno));
		return;
	}
	fputs(bind ? UDC_NAME "\n" : "\n", f);
	fclose(f);
}

/* Waits until Android's USB service has switched to the accessory function
 * (legacy handler: sys.usb.state), at most timeout_ms. */
static int wait_accessory_state(int timeout_ms)
{
	char value[PROP_VALUE_MAX];
	for (; timeout_ms > 0; timeout_ms -= 100) {
		if (__system_property_get("sys.usb.state", value) > 0
				&& strstr(value, "accessory") != NULL)
			return 0;
		usleep(100 * 1000);
	}
	return -ETIMEDOUT;
}

static int control(int fd, uint8_t type, uint8_t req, uint16_t value,
		uint16_t index, void *data, uint16_t len)
{
	struct usbdevfs_ctrltransfer ct = {
		.bRequestType = type, .bRequest = req, .wValue = value,
		.wIndex = index, .wLength = len, .timeout = 1000, .data = data,
	};
	return ioctl(fd, USBDEVFS_CONTROL, &ct);
}

/* Android Open Accessory handshake: protocol, identification, start. */
static int aoa_start(int fd)
{
	uint8_t proto[2] = { 0, 0 };
	size_t i;
	if (control(fd, USB_DIR_IN | USB_TYPE_VENDOR, AOA_GET_PROTOCOL, 0, 0,
			proto, 2) < 0)
		return -errno;
	logf_("aoa: protocol %u", proto[0] | (proto[1] << 8));
	for (i = 0; i < sizeof(strings) / sizeof(strings[0]); i++) {
		if (control(fd, USB_DIR_OUT | USB_TYPE_VENDOR, AOA_SEND_STRING, 0,
				(uint16_t)i, (void *)strings[i],
				(uint16_t)(strlen(strings[i]) + 1)) < 0)
			return -errno;
	}
	if (control(fd, USB_DIR_OUT | USB_TYPE_VENDOR, AOA_START, 0, 0, NULL, 0) < 0)
		return -errno;
	return 0;
}

/* Bulk endpoints of interface 0, from the descriptors usbfs returns on read. */
static int find_endpoints(int fd, struct link *l)
{
	uint8_t buf[1024];
	ssize_t n, i;
	int iface = -1;
	lseek(fd, 0, SEEK_SET);
	n = read(fd, buf, sizeof(buf));
	l->ep_in = l->ep_out = 0;
	for (i = 0; i + 2 <= n && buf[i] >= 2; i += buf[i]) {
		if (buf[i + 1] == USB_DT_INTERFACE)
			iface = buf[i + 2];
		else if (buf[i + 1] == USB_DT_ENDPOINT && iface == 0
				&& (buf[i + 3] & USB_ENDPOINT_XFERTYPE_MASK) == USB_ENDPOINT_XFER_BULK) {
			if (buf[i + 2] & USB_DIR_IN) {
				if (!l->ep_in)
					l->ep_in = buf[i + 2];
			} else if (!l->ep_out) {
				l->ep_out = buf[i + 2];
			}
		}
	}
	return l->ep_in && l->ep_out ? 0 : -ENOENT;
}

static int bulk(int fd, unsigned char ep, void *data, unsigned len, unsigned timeout)
{
	struct usbdevfs_bulktransfer bt = {
		.ep = ep, .len = len, .timeout = timeout, .data = data,
	};
	return ioctl(fd, USBDEVFS_BULK, &bt);
}

/* USB IN (phone to controller) -> TCP */
static void *usb_to_tcp(void *arg)
{
	struct link *l = arg;
	static uint8_t buf[BULK_MAX];
	while (!l->stop) {
		int n = bulk(l->usb, l->ep_in, buf, sizeof(buf), 500);
		if (n < 0) {
			if (errno == ETIMEDOUT)
				continue;
			logf_("usb in: %s", strerror(errno));
			break;
		}
		if (n > 0 && send(l->sock, buf, (size_t)n, MSG_NOSIGNAL) != n) {
			logf_("tcp send: %s", strerror(errno));
			break;
		}
	}
	l->stop = 1;
	return NULL;
}

/* TCP -> USB OUT (controller to phone) */
static void *tcp_to_usb(void *arg)
{
	struct link *l = arg;
	static uint8_t buf[BULK_MAX];
	while (!l->stop) {
		struct pollfd p = { .fd = l->sock, .events = POLLIN };
		ssize_t n;
		if (poll(&p, 1, 500) <= 0)
			continue;
		n = recv(l->sock, buf, sizeof(buf), 0);
		if (n <= 0) {
			logf_("tcp closed");
			break;
		}
		if (bulk(l->usb, l->ep_out, buf, (unsigned)n, 2000) != n) {
			logf_("usb out: %s", strerror(errno));
			break;
		}
	}
	l->stop = 1;
	return NULL;
}

static int tcp_connect(const char *host, int port)
{
	struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons((uint16_t)port) };
	int one = 1;
	int s = socket(AF_INET, SOCK_STREAM, 0);
	if (s < 0)
		return -1;
	inet_pton(AF_INET, host, &a.sin_addr);
	if (connect(s, (struct sockaddr *)&a, sizeof(a)) < 0) {
		close(s);
		return -1;
	}
	setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
	return s;
}

int main(int argc, char *argv[])
{
	const char *host = "10.0.2.2";
	int port = 4321;
	int i;
	for (i = 1; i + 1 < argc; i += 2) {
		if (strcmp(argv[i], "--host") == 0)
			host = argv[i + 1];
		else if (strcmp(argv[i], "--port") == 0)
			port = atoi(argv[i + 1]);
	}
	signal(SIGPIPE, SIG_IGN);
	setvbuf(stderr, NULL, _IOLBF, 0);

	/* Started from an adb shell, this process lives in adbd's cgroup, and
	 * Android restarts adbd when the USB functions change (each plug): init
	 * would kill it with adbd. Move to the root cgroup and a new session. */
	{
		FILE *f = fopen("/sys/fs/cgroup/cgroup.procs", "w");
		if (f != NULL) {
			fprintf(f, "%d\n", getpid());
			fclose(f);
		} else {
			logf_("cgroup: %s", strerror(errno));
		}
		setsid();
	}

	for (;;) {
		char path[64];
		struct link l = { .usb = -1, .sock = -1 };
		pthread_t a, b;
		int iface = 0;

		if (!find_device(path, sizeof(path))) {
			sleep(1);
			continue;
		}
		l.usb = open(path, O_RDWR);
		if (l.usb < 0) {
			logf_("open %s: %s", path, strerror(errno));
			sleep(1);
			continue;
		}
		if (aoa_start(l.usb) < 0) {
			logf_("aoa handshake on %s: %s", path, strerror(errno));
			close(l.usb);
			sleep(1);
			continue;
		}
		logf_("aoa: started on %s as %s %s", path, strings[0], strings[1]);
		close(l.usb);
		/* Android switches to accessory mode, then the gadget
		 * re-enumerates; the accessory is the device seen after that. */
		if (wait_accessory_state(5000) < 0)
			logf_("android did not switch to accessory mode");
		udc_bind(0);
		usleep(500 * 1000);
		udc_bind(1);
		sleep(1);
		if (!find_device(path, sizeof(path)) || (l.usb = open(path, O_RDWR)) < 0) {
			logf_("accessory did not come back: %s", strerror(errno));
			sleep(1);
			continue;
		}
		if (find_endpoints(l.usb, &l) < 0
				|| ioctl(l.usb, USBDEVFS_CLAIMINTERFACE, &iface) < 0) {
			logf_("accessory interface: %s", strerror(errno));
			close(l.usb);
			sleep(1);
			continue;
		}
		/* Wait for the phone to open the accessory and speak first (the
		 * app's mux), so nothing is written to an accessory nobody reads. */
		{
			static uint8_t first[BULK_MAX];
			int n;
			do {
				n = bulk(l.usb, l.ep_in, first, sizeof(first), 1000);
			} while (n < 0 && errno == ETIMEDOUT);
			if (n <= 0) {
				logf_("usb in (waiting for the phone): %s", n < 0 ? strerror(errno) : "empty");
				ioctl(l.usb, USBDEVFS_RELEASEINTERFACE, &iface);
				close(l.usb);
				sleep(1);
				continue;
			}
			logf_("phone opened the accessory (%d bytes)", n);
			l.sock = tcp_connect(host, port);
			if (l.sock >= 0 && send(l.sock, first, (size_t)n, MSG_NOSIGNAL) != n) {
				close(l.sock);
				l.sock = -1;
			}
		}
		if (l.sock < 0) {
			logf_("connect %s:%d: %s", host, port, strerror(errno));
			ioctl(l.usb, USBDEVFS_RELEASEINTERFACE, &iface);
			close(l.usb);
			sleep(1);
			continue;
		}
		logf_("relaying %s (in 0x%02x, out 0x%02x) <-> %s:%d",
				path, l.ep_in, l.ep_out, host, port);
		pthread_create(&a, NULL, usb_to_tcp, &l);
		pthread_create(&b, NULL, tcp_to_usb, &l);
		pthread_join(a, NULL);
		pthread_join(b, NULL);
		logf_("link closed: unplug, then plug again");
		close(l.sock);
		ioctl(l.usb, USBDEVFS_RELEASEINTERFACE, &iface);
		close(l.usb);
		udc_bind(0);
		sleep(1);
		udc_bind(1);
		sleep(1);
	}
	return 0;
}

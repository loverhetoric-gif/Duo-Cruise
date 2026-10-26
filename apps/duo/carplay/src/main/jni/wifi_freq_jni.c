/*
 * Duo Cruise: Wi-Fi の口(例 wlan2)がいま使っている周波数(MHz)を、Linux の nl80211 から読む。
 * 読むだけ(NL80211_CMD_GET_INTERFACE)。設定は変えない。読めなければ 0 を返す。
 * LocalOnlyHotspot は普通のアプリにチャンネルを教えないため、iPhone に正しいチャンネルを渡すのに使う。
 */
#include <jni.h>
#include <errno.h>
#include <linux/genetlink.h>
#include <linux/netlink.h>
#include <net/if.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#define NL80211_CMD_GET_INTERFACE 5
#define NL80211_ATTR_IFINDEX 3
#define NL80211_ATTR_WIPHY_FREQ 38

#define NLA_DATA(nla) ((void *)((char *)(nla) + NLA_HDRLEN))

struct req {
    struct nlmsghdr n;
    struct genlmsghdr g;
    char buf[256];
};

static void add_attr(struct req *r, unsigned short type, const void *data, int len) {
    struct nlattr *a = (struct nlattr *)((char *)r + NLMSG_ALIGN(r->n.nlmsg_len));
    a->nla_type = type;
    a->nla_len = (unsigned short)(NLA_HDRLEN + len);
    memcpy(NLA_DATA(a), data, (size_t)len);
    r->n.nlmsg_len = NLMSG_ALIGN(r->n.nlmsg_len) + NLA_ALIGN(a->nla_len);
}

/* 応答の属性から type を探し、値の先頭を返す。無ければ NULL。 */
static void *find_attr(struct nlmsghdr *h, unsigned short type, int *len) {
    int rem = (int)h->nlmsg_len - NLMSG_HDRLEN - GENL_HDRLEN;
    struct nlattr *a = (struct nlattr *)((char *)NLMSG_DATA(h) + GENL_HDRLEN);
    while (rem >= NLA_HDRLEN && a->nla_len >= NLA_HDRLEN && a->nla_len <= rem) {
        if ((a->nla_type & NLA_TYPE_MASK) == type) {
            if (len) *len = a->nla_len - NLA_HDRLEN;
            return NLA_DATA(a);
        }
        rem -= NLA_ALIGN(a->nla_len);
        a = (struct nlattr *)((char *)a + NLA_ALIGN(a->nla_len));
    }
    return NULL;
}

static int talk(int fd, struct req *r, char *resp, int resp_size) {
    struct sockaddr_nl to = {.nl_family = AF_NETLINK};
    if (sendto(fd, r, r->n.nlmsg_len, 0, (struct sockaddr *)&to, sizeof to) < 0) return -1;
    return (int)recv(fd, resp, (size_t)resp_size, 0);
}

static int family_id(int fd) {
    struct req r;
    memset(&r, 0, sizeof r);
    r.n.nlmsg_len = NLMSG_LENGTH(GENL_HDRLEN);
    r.n.nlmsg_type = GENL_ID_CTRL;
    r.n.nlmsg_flags = NLM_F_REQUEST;
    r.n.nlmsg_seq = 1;
    r.g.cmd = CTRL_CMD_GETFAMILY;
    r.g.version = 1;
    add_attr(&r, CTRL_ATTR_FAMILY_NAME, "nl80211", 8);
    char resp[4096];
    int n = talk(fd, &r, resp, sizeof resp);
    if (n <= 0) return -1;
    struct nlmsghdr *h = (struct nlmsghdr *)resp;
    if (!NLMSG_OK(h, (unsigned)n) || h->nlmsg_type == NLMSG_ERROR) return -1;
    int len = 0;
    unsigned short *id = find_attr(h, CTRL_ATTR_FAMILY_ID, &len);
    return (id && len >= 2) ? *id : -1;
}

JNIEXPORT jint JNICALL
Java_com_yuki_duocruise_carplay_WifiFrequency_nativeFrequencyMHz(JNIEnv *env, jclass cls, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, 0);
    unsigned int ifindex = if_nametoindex(name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    if (ifindex == 0) return 0;

    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_GENERIC);
    if (fd < 0) return 0;
    struct timeval tv = {.tv_sec = 1, .tv_usec = 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    struct sockaddr_nl me = {.nl_family = AF_NETLINK};
    if (bind(fd, (struct sockaddr *)&me, sizeof me) < 0) { close(fd); return 0; }

    int fam = family_id(fd);
    if (fam < 0) { close(fd); return 0; }

    struct req r;
    memset(&r, 0, sizeof r);
    r.n.nlmsg_len = NLMSG_LENGTH(GENL_HDRLEN);
    r.n.nlmsg_type = (unsigned short)fam;
    r.n.nlmsg_flags = NLM_F_REQUEST;
    r.n.nlmsg_seq = 2;
    r.g.cmd = NL80211_CMD_GET_INTERFACE;
    r.g.version = 0;
    add_attr(&r, NL80211_ATTR_IFINDEX, &ifindex, 4);
    char resp[8192];
    int n = talk(fd, &r, resp, sizeof resp);
    close(fd);
    if (n <= 0) return 0;
    struct nlmsghdr *h = (struct nlmsghdr *)resp;
    if (!NLMSG_OK(h, (unsigned)n) || h->nlmsg_type == NLMSG_ERROR) return 0;
    int len = 0;
    unsigned int *freq = find_attr(h, NL80211_ATTR_WIPHY_FREQ, &len);
    return (freq && len >= 4) ? (jint)*freq : 0;
}

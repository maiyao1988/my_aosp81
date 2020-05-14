#include "art_field-inl.h"
#include "art_field.h"
#include "art_method.h"
#include "art_method-inl.h"
#include "base/enums.h"
#include "base/logging.h"
#include "base/macros.h"
#include "class_linker-inl.h"
#include "common_dex_operations.h"
#include "common_throws.h"
#include "dex_file-inl.h"
#include "dex_instruction-inl.h"


#include <set>
#include <cstdio>
#include <ctype.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <android/log.h>
#define TAG "unshell"

struct Config {
    int smethodlogUid;
    bool sUseDexDump;
    char sPkgName[256];
    char hackDir[256];
};

static const char *hackDir = "/data/local/tmp/hack";
static Config s_cfg;

static const char *trimCpy(char *dest, const char *src) {
    char *q = dest;
    const char *p = src;
    while(*p) {
        if (!isspace(*p)) {
            *q++ = *p++;
        }
        else {
            p++;
        }
    }
    return dest;
}

__attribute__((constructor)) static void init(){
    char cfgPath[255];
    sprintf(cfgPath, "%s/cfg.txt", hackDir);
    strcpy(s_cfg.hackDir, hackDir);

    s_cfg.smethodlogUid = -1;
    FILE *f = fopen(cfgPath, "rb");
    if (!f) {
        __android_log_print(ANDROID_LOG_FATAL, TAG, "cfg %s not found skip", cfgPath);
        return;
    }
    char buf[500];
    while (fgets(buf, sizeof(buf), f) != NULL) {
        if (buf[0] == '#')
        {
            continue;
        }
        char *p = strchr(buf, '=');
        if (p) {
            *p = 0;
            const char *key = buf;
            const char *val = p + 1;
            __android_log_print(ANDROID_LOG_FATAL, TAG, "key=%s, val=%s", key, val);
            if (strcmp(key, "useDexDump") == 0) {
                s_cfg.sUseDexDump = (*val) != '0';
                __android_log_print(ANDROID_LOG_FATAL, TAG, "use dex %d", s_cfg.sUseDexDump);
            }
            if (strcmp(key, "methodLogUid") == 0) {
                char suid[300];
                trimCpy(suid, val);
                s_cfg.smethodlogUid = atoi(suid);
                __android_log_print(ANDROID_LOG_FATAL, TAG, "use method logs uid:%d", s_cfg.smethodlogUid);
            }
            if (strcmp(key, "pkgName") == 0) {
                trimCpy(s_cfg.sPkgName, val);
                __android_log_print(ANDROID_LOG_FATAL, TAG, "pkgName = %s", s_cfg.sPkgName);
            }
        }
    }
    fclose(f);
}

const Config &get_config() {
    return s_cfg;
}

using namespace art;

void hack_method_invoke(ArtMethod *m) REQUIRES_SHARED(Locks::mutator_lock_) {
    const Config &cfg = get_config();
    if (cfg.smethodlogUid != (int)getuid()) {
        return;
    }
    const char *name = m->GetName();
    std::string sig_as_string(m->GetSignature().ToString());
    const char *sig = sig_as_string.c_str();
    const char *clsDesc = m->GetDeclaringClassDescriptor();

     __android_log_print(ANDROID_LOG_FATAL, "method_traces", "%s->%s%s", clsDesc, name, sig);
}


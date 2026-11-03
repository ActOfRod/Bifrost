// Neutral implementations of the Link's Awakening-specific hooks the vendored renderer calls (widescreen,
// randomizer tints, debug tools, LAS's ImGui menu). Generic NVN titles get the plain renderer behaviour.
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

typedef union SDL_Event SDL_Event;

volatile uint64_t g_vsync_count;
volatile int g_debug_frame_request;
void plat_render_work(uint64_t work_ns, uint64_t target_ns) { (void)work_ns, (void)target_ns; }
void android_log_file(bool on) { (void)on; }
void input_frame(uint64_t frame) { (void)frame; }
int input_pad_family(void) { return 0; }
void menu_redraw(void *cmd) { (void)cmd; }
void menu_test_open(void) {}
bool touch_controls_event(const SDL_Event *e) { (void)e; return false; }
bool doorjump_link_pos(float p[3]) { (void)p; return false; }
void escape_bottle_tint(int kind, const float *ui, uint32_t ui_size, float tint[8]) { (void)kind, (void)ui, (void)ui_size, (void)tint; }
void fnprof_frame(uint64_t frame) { (void)frame; }
const char *game_level_name(void) { return ""; }
bool mirror_draw(bool composite) { (void)composite; return false; }
bool pets_hide_item_cursor(void) { return false; }
bool pot_glow_active(void) { return false; }
void pot_gold_tint(float tint[8]) { (void)tint; }
bool pot_instance_gold(const float *m) { (void)m; return false; }
bool pot_level_active(void) { return false; }
bool pot_tint(const float *m, float tint[8], uint64_t mesh) { (void)m, (void)tint, (void)mesh; return false; }
void reboot_test_frame(uint64_t frame) { (void)frame; }
void save_editor_frame(uint64_t frame) { (void)frame; }
void scene_size_log(bool composite, uint32_t sw, uint32_t sh, uint32_t w, uint32_t h) { (void)composite, (void)sw, (void)sh, (void)w, (void)h; }
void warp_frame(uint64_t frame) { (void)frame; }
bool widescreen_allowed(void) { return false; }
void widescreen_backdrop_seen(uint64_t hash) { (void)hash; }
bool widescreen_camera(uint64_t frame, float vp[16]) { (void)frame, (void)vp; return false; }
float widescreen_effect_stretch(uint64_t fs_hash) { (void)fs_hash; return 1.0f; }
float widescreen_factor(void) { return 1.0f; }
void widescreen_frame_end(uint64_t frame) { (void)frame; }
void widescreen_present_crop(uint64_t a, double b, float *c, double *d, double *e, double *f, double *g) {
    (void)a, (void)b, (void)c, (void)d, (void)e, (void)f, (void)g;
}
void widescreen_scene_ubo(const float *u, uint64_t size) { (void)u, (void)size; }
void widescreen_set_factor(float f) { (void)f; }
bool widescreen_ui_pane_partial(const float *u, uint64_t size) { (void)u, (void)size; return false; }

// LAS's in-game ImGui menu, input binding and randomizer: not part of Bifrost's generic renderer.
typedef struct SDL_Window SDL_Window;
void rt_window_icon(SDL_Window *w) { (void)w; }
void input_handle_event(const SDL_Event *e) { (void)e; }
bool input_user_blocked(const SDL_Event *e) { (void)e; return false; }
void menu_apply_window_requests(void *sdl_window) { (void)sdl_window; }
bool menu_handle_event(const SDL_Event *e) { (void)e; return false; }
bool menu_is_open(void) { return false; }
void menu_render(void *cmd, uint32_t target_w, uint32_t target_h) { (void)cmd, (void)target_w, (void)target_h; }
int bind_prompt_host(int slot) { (void)slot; return 0; }
const char *rando_seed_dir(void) { return NULL; }

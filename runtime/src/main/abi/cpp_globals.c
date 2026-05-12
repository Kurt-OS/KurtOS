void *__dso_handle = 0;
void * _ZTISt9exception[4] = {0};
void * _ZTVN10__cxxabiv117__class_type_infoE[4] = {0};
void * _ZTVN10__cxxabiv120__si_class_type_infoE[4] = {0};
void * _ZTVN10__cxxabiv121__vmi_class_type_infoE[4] = {0};

typedef struct {
    unsigned char rehash;
    unsigned long bucket_count;
} cpp_rehash_decision_t;

cpp_rehash_decision_t _ZNKSt8__detail20_Prime_rehash_policy14_M_need_rehashEmmm(
    void *policy,
    unsigned long bucket_count,
    unsigned long element_count,
    unsigned long insert_count
) {
    (void)policy;
    (void)element_count;
    (void)insert_count;

    cpp_rehash_decision_t decision = {0, bucket_count};
    return decision;
}

typedef struct cpp_list_node_base {
    struct cpp_list_node_base *next;
    struct cpp_list_node_base *prev;
} cpp_list_node_base_t;

void _ZNSt8__detail15_List_node_base7_M_hookEPS0_(
    cpp_list_node_base_t *node,
    cpp_list_node_base_t *position
) {
    node->next = position;
    node->prev = position->prev;
    position->prev->next = node;
    position->prev = node;
}

void _ZNSt8__detail15_List_node_base11_M_transferEPS0_S1_(
    cpp_list_node_base_t *position,
    cpp_list_node_base_t *first,
    cpp_list_node_base_t *last
) {
    if (position == last) {
        return;
    }

    last->prev->next = position;
    first->prev->next = last;
    position->prev->next = first;

    cpp_list_node_base_t *old_position_prev = position->prev;
    position->prev = last->prev;
    last->prev = first->prev;
    first->prev = old_position_prev;
}

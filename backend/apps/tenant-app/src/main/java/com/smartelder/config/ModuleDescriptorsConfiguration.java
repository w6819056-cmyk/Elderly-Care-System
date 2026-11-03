package com.smartelder.config;

import com.smartelder.module.api.ModuleDependency;
import com.smartelder.module.api.ModuleDescriptor;
import com.smartelder.module.api.ModuleMenuEntry;
import com.smartelder.module.api.ModuleResourceOperation;
import com.smartelder.module.api.ModuleRegistry;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(InstalledModulesRegistrar.class)
public class ModuleDescriptorsConfiguration {
    @Bean
    ModuleRegistry moduleRegistry(List<ModuleDescriptor> descriptors,InstalledModulesRegistrar.RuntimeState runtime) {
        var modules=new java.util.LinkedHashMap<String,ModuleDescriptor>();
        descriptors.forEach(d->modules.put(d.code(),d));
        runtime.loaded().forEach(m->modules.put(m.manifest().module().code(),m.manifest().module()));
        return new ModuleRegistry(modules.values());
    }
    @Bean
    ModuleDescriptor membershipModuleDescriptor() {
        return new ModuleDescriptor("membership", "会员运营", "1.0.0", 10, List.of(),
                Set.of("membership:member:read", "membership:member:write"),
                List.of(new ModuleMenuEntry("membership", "会员管理", "/membership", "users", "membership:member:read")),
                Set.of(new ModuleResourceOperation("MembershipResource", "read", "membership:member:read", true, false),
                        new ModuleResourceOperation("MembershipResource", "write", "membership:member:write", true, true)),
                Set.of("MembershipResource#complete_existing_redemption"));
    }
    @Bean
    ModuleDescriptor institutionModuleDescriptor() {
        return new ModuleDescriptor("institution", "机构康养", "1.0.0", 20,
                List.of(new ModuleDependency("membership", "1.0.0")),
                Set.of("institution:bed:read", "institution:bed:write"),
                List.of(new ModuleMenuEntry("institution", "机构康养", "/institution", "building", "institution:bed:read")),
                Set.of(new ModuleResourceOperation("InstitutionResource", "read", "institution:bed:read", true, false),
                        new ModuleResourceOperation("InstitutionResource", "write", "institution:bed:write", true, true)),
                Set.of("InstitutionResource#complete_existing_admission"));
    }
    @Bean
    ModuleDescriptor communityModuleDescriptor() {
        return new ModuleDescriptor("community", "社区居家", "1.0.0", 30, List.of(),
                Set.of("community:service:read", "community:service:write"),
                List.of(new ModuleMenuEntry("community", "社区居家", "/community", "heart-handshake", "community:service:read")),
                Set.of(new ModuleResourceOperation("CommunityResource", "read", "community:service:read", true, false),
                        new ModuleResourceOperation("CommunityResource", "write", "community:service:write", true, true)),
                Set.of("CommunityResource#complete_started_service"));
    }
}

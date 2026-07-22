package cn.entertech.plugin.demo.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class DemoPublishPlugin implements Plugin<Project> {
    public static final String EXTENSION_NAME = "demoPublish";
    public static final String PRINT_TASK_NAME = "printDemoPublishInfo";

    @Override
    public void apply(Project project) {
        DemoPublishExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, DemoPublishExtension.class);

        project.getTasks().register(PRINT_TASK_NAME, task -> {
            task.setGroup("demo");
            task.setDescription("Prints the message configured by the demoPublish extension.");
            task.doLast(ignored -> project.getLogger().lifecycle(extension.getMessage()));
        });
    }
}

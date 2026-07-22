package cn.entertech.plugin.demo.plugin;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class DemoPublishPluginTest {
    @Test
    public void applyRegistersExtensionAndPrintTask() {
        Project project = ProjectBuilder.builder().build();

        new DemoPublishPlugin().apply(project);

        DemoPublishExtension extension = project.getExtensions().findByType(DemoPublishExtension.class);
        assertNotNull(extension);
        assertEquals("Hello from demo publish plugin", extension.getMessage());

        Task task = project.getTasks().findByName(DemoPublishPlugin.PRINT_TASK_NAME);
        assertNotNull(task);
        assertEquals("demo", task.getGroup());
    }
}

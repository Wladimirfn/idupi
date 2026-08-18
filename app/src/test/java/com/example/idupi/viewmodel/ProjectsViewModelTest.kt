package com.example.idupi.viewmodel

import com.example.idupi.FakeClientSource
import com.example.idupi.FakeIduPiClient
import com.example.idupi.MainDispatcherRule
import com.example.idupi.domain.model.Project
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ProjectsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    @Test
    fun `init loads projects from client`() = runTest {
        fake.projectsToReturn = listOf(Project(id = "p1", name = "Project One", path = "/p1", isActive = true))

        val viewModel = ProjectsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(fake.projectsToReturn, viewModel.projects.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `refreshProjects sets errorMessage when client throws`() = runTest {
        fake.failWith = RuntimeException("projects unavailable")

        val viewModel = ProjectsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("projects unavailable"))
    }

    @Test
    fun `selectProject delegates to client with the given id`() = runTest {
        val viewModel = ProjectsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        viewModel.selectProject("p2")
        advanceUntilIdle()

        assertEquals("p2", fake.lastSelectedProjectId)
        assertEquals("p2", viewModel.activeProjectId.value)
    }

    @Test
    fun `clearError resets errorMessage to null`() = runTest {
        fake.failWith = RuntimeException("boom")
        val viewModel = ProjectsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertNotNull(viewModel.errorMessage.value)

        viewModel.clearError()

        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `addNewProject sets addProjectError when client throws`() = runTest {
        val viewModel = ProjectsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("add project failed")
        var onSuccessCalled = false
        viewModel.addNewProject("New Project", "/path", onSuccess = { onSuccessCalled = true })
        advanceUntilIdle()

        assertNotNull(viewModel.addProjectError.value)
        assertTrue(viewModel.addProjectError.value!!.contains("add project failed"))
        assertEquals(false, onSuccessCalled)
        assertEquals(false, viewModel.isAddingProject.value)
    }

    @Test
    fun `loadBrowsePath loads directory browse result and navigateBrowseUp navigates to parent`() = runTest {
        val viewModel = ProjectsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        val sampleBrowse = com.example.idupi.domain.model.DirectoryBrowseResponse(
            currentPath = "C:\\Users\\Developer\\dev",
            parentPath = "C:\\Users\\Developer",
            directories = listOf(
                com.example.idupi.domain.model.RemoteDirectoryItem(name = "SampleProject", path = "C:\\Users\\Developer\\dev\\SampleProject", isProject = true)
            )
        )
        fake.browseDirectoryToReturn = sampleBrowse

        viewModel.loadBrowsePath("C:\\Users\\Developer\\dev")
        advanceUntilIdle()

        assertEquals("C:\\Users\\Developer\\dev", fake.lastBrowsedPath)
        assertEquals(sampleBrowse, viewModel.browseData.value)
        assertEquals(false, viewModel.isBrowsing.value)

        viewModel.navigateBrowseUp()
        advanceUntilIdle()

        assertEquals("C:\\Users\\Developer", fake.lastBrowsedPath)
    }

    @Test
    fun `multi selection mode toggles selectAll and clearSelection correctly`() = runTest {
        fake.projectsToReturn = listOf(
            Project(id = "p1", name = "Project One", path = "/p1", isActive = false),
            Project(id = "p2", name = "Project Two", path = "/p2", isActive = false)
        )
        val viewModel = ProjectsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        viewModel.toggleProjectSelection("p1")
        assertEquals(setOf("p1"), viewModel.selectedProjectIds.value)
        assertEquals(true, viewModel.isSelectionMode.value)

        viewModel.selectAllProjects()
        assertEquals(setOf("p1", "p2"), viewModel.selectedProjectIds.value)
        assertEquals(true, viewModel.isSelectionMode.value)

        viewModel.clearSelection()
        assertEquals(emptySet<String>(), viewModel.selectedProjectIds.value)
        assertEquals(false, viewModel.isSelectionMode.value)
    }

    @Test
    fun `removeProjects calls client and clears selection on success`() = runTest {
        val viewModel = ProjectsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        var onSuccessCalled = false
        viewModel.toggleProjectSelection("p1")
        viewModel.removeProjects(listOf("p1"), deleteFiles = true, onSuccess = { onSuccessCalled = true })
        advanceUntilIdle()

        assertEquals(listOf("p1"), fake.lastRemovedProjectIds)
        assertEquals(true, fake.lastDeleteFilesFlag)
        assertEquals(true, onSuccessCalled)
        assertEquals(emptySet<String>(), viewModel.selectedProjectIds.value)
        assertEquals(false, viewModel.isSelectionMode.value)
    }
}

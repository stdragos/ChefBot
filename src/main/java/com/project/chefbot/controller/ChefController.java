package com.project.chefbot.controller;

import com.project.chefbot.dto.CreateSessionRequest;
import com.project.chefbot.model.User;
import com.project.chefbot.repository.UserRepository;
import com.project.chefbot.service.ChefAiService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/chef")
@RequiredArgsConstructor
public class ChefController {

    private final ChefAiService aiService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return null;
        String username = authentication.getName();
        var user = userRepository.findByUsername(username);
        return user != null ? user.getId() : null;
    }

    @GetMapping("/new")
    public String showForm(Model model) {
        model.addAttribute("request", new CreateSessionRequest());
        Long userId = getCurrentUserId();
        var allSessions = aiService.getSessionsForUser(userId);
        model.addAttribute("allSessions", allSessions);
        model.addAttribute("userId", userId);
        return "create-session";
    }

    @PostMapping("/new")
    public String createSession(@Valid @ModelAttribute("request") CreateSessionRequest request,
                                BindingResult result, Model model) {
        Long userId = getCurrentUserId();
        if (result.hasErrors()) {
            var allSessions = aiService.getSessionsForUser(userId);
            model.addAttribute("allSessions", allSessions);
            model.addAttribute("userId", userId);
            return "create-session";
        }
        request.setUserId(userId);
        Long sessionId = aiService.createSession(request);
        return "redirect:/chef/chat/" + sessionId;
    }

    @GetMapping("/chat/{id}")
    public String showChat(@PathVariable Long id, Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");

        var session = aiService.getSessionInfo(id);
        var messages = aiService.getMessagesForSession(id);
        Long userId = getCurrentUserId();
        var allSessions = aiService.getSessionsForUser(userId);

        model.addAttribute("session", session);
        model.addAttribute("sessionName", session.getSessionName());
        model.addAttribute("dietType", session.getDietType());
        model.addAttribute("excludedIngredients", session.getExcludedIngredients());
        model.addAttribute("chefPersonality", session.getChefPersonality());
        model.addAttribute("messagesList", messages);
        model.addAttribute("sessionId", id);
        model.addAttribute("allSessions", allSessions);
        model.addAttribute("userId", userId);

        return "chat";
    }

    @PostMapping("/chat/{id}")
    public String sendMessage(@PathVariable Long id, @RequestParam String message) {
        aiService.sendMessage(id, message);
        return "redirect:/chef/chat/" + id;
    }

    @PostMapping("/delete-session/{id}")
    public String deleteSession(@PathVariable Long id, @RequestParam(required = false) Long userId) {
        if (userId == null) {
            userId = getCurrentUserId();
        }
        aiService.deleteSession(id, userId);
        return "redirect:/chef/new";
    }

    @GetMapping("/register")
    public String showRegisterForm(Model model) {
        model.addAttribute("user", new User());
        return "register";
    }

    @PostMapping("/register")
    public String registerUser(@ModelAttribute("user") User user, Model model) {
        if (user.getUsername() == null || user.getUsername().isBlank() || user.getPassword() == null || user.getPassword().isBlank()) {
            model.addAttribute("error", "Username and password are required!");
            return "register";
        }
        if (userRepository.findByUsername(user.getUsername()) != null) {
            model.addAttribute("error", "This username already exists!");
            return "register";
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        userRepository.save(user);
        return "redirect:/login?registerSuccess";
    }
}
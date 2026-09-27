package com.mafia.classicmafia.api;

import com.mafia.classicmafia.api.dto.ApiPageResponse;
import com.mafia.classicmafia.api.dto.rating.RatingRowResponse;
import com.mafia.classicmafia.service.GameService;
import com.mafia.classicmafia.view.GameRankingRow;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/rating")
public class RatingApiController {
    private final GameService games;

    public RatingApiController(GameService games) {
        this.games = games;
    }

    @GetMapping
    ApiPageResponse<RatingRowResponse> rating(
        @RequestParam(defaultValue = "points") String sort,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        String normalizedSort = sort == null ? "points" : sort.strip().toLowerCase();
        if (!normalizedSort.equals("points") && !normalizedSort.equals("weighted")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SORT_INVALID", "Допустимая сортировка: points или weighted.");
        }
        if (page < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_INVALID", "Номер страницы не может быть отрицательным.");
        if (size < 1 || size > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_SIZE_INVALID", "Размер страницы должен быть от 1 до 100.");

        List<GameRankingRow> full = games.buildRanking(normalizedSort);
        long offset = (long) page * size;
        int from = (int) Math.min(offset, full.size());
        int to = Math.min(from + size, full.size());
        List<RatingRowResponse> items = new ArrayList<>();
        for (int index = from; index < to; index++) {
            GameRankingRow row = full.get(index);
            items.add(new RatingRowResponse(
                index + 1,
                row.getDisplayName(),
                row.getUserId(),
                row.getGames(),
                row.getWins(),
                row.getTotalPoints(),
                row.getAverageAdditionalPoints(),
                row.getWinRate(),
                row.getWeightedScore()
            ));
        }
        int totalPages = full.isEmpty() ? 0 : (int) Math.ceil((double) full.size() / size);
        return new ApiPageResponse<>(items, page, size, full.size(), totalPages);
    }
}

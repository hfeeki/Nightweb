(ns net.nightweb.views
  (:use [neko.ui :only [make-ui]]
        [neko.threading :only [on-ui]]
        [neko.resource :only [get-string get-resource]]
        [neko.notify :only [toast]]
        [net.nightweb.utils :only [full-size
                                   thumb-size
                                   path-to-bitmap
                                   get-pic-path
                                   make-dip
                                   load-pic
                                   default-text-size
                                   set-text-size
                                   set-text-max-length
                                   set-text-content]]
        [net.nightweb.actions :only [show-spinner
                                     clear-attachments
                                     send-post
                                     tile-action
                                     save-profile
                                     cancel
                                     toggle-fav]]
        [net.nightweb.dialogs :only [show-delete-post-dialog
                                     show-remove-user-dialog
                                     show-edit-post-dialog
                                     show-profile-dialog]]
        [nightweb.db :only [limit
                            get-single-user-data
                            get-post-data
                            get-pic-data
                            get-single-post-data
                            get-single-fav-data
                            get-category-data
                            get-single-tag-data]]
        [nightweb.formats :only [remove-dupes-and-nils
                                 base32-encode
                                 tags-encode]]
        [nightweb.constants :only [is-me?]]))

(def default-tile-width 160)

(defn add-last-tile
  "Adds a tile to take you to the next page if necessary."
  [content results]
  (if (> (count results) limit)
    (let [next-page (-> (get content :page)
                        (or 1)
                        (+ 1))]
      (-> results
          (pop)
          (conj (assoc content
                       :title (str (get-string :page) " " next-page)
                       :background (get-resource :drawable :next)
                       :add-emphasis? true
                       :page next-page))))
    results))

(defn set-grid-view-tiles
  "Sets the content in the given grid view."
  [context content view]
  (let [num-columns (.getNumColumns view)
        width (.getWidth view)
        tile-view-width (if (and (> width 0) (> num-columns 0))
                          (int (/ width num-columns))
                          (make-dip context default-tile-width))
        layout-params (android.widget.AbsListView$LayoutParams.
                                                  tile-view-width
                                                  tile-view-width)]
    (.setAdapter
      view
      (proxy [android.widget.BaseAdapter] []
        (getItem [position] (get content position))
        (getItemId [position] 0)
        (getCount [] (count content))
        (getView [position convert-view parent]
          (let [white android.graphics.Color/WHITE
                not-initialized (nil? convert-view)
                tile-view (if not-initialized
                            (make-ui context
                                     [:frame-layout {}
                                      [:image-view {}]
                                      [:linear-layout {:orientation 1}
                                       [:text-view {:text-color white
                                                    :layout-height 0
                                                    :layout-width :fill
                                                    :layout-weight 1}]
                                       [:linear-layout {:orientation 0}
                                        [:text-view {:text-color white
                                                     :layout-weight 1}]
                                        [:text-view {:text-color white}]]]])
                            convert-view)
                item (get content position)
                img (.getChildAt tile-view 0)
                linear-layout (.getChildAt tile-view 1)
                text-top (.getChildAt linear-layout 0)
                bottom-layout (.getChildAt linear-layout 1)
                text-bottom (.getChildAt bottom-layout 0)
                text-count (.getChildAt bottom-layout 1)]
            (when not-initialized
              (.setScaleType img android.widget.ImageView$ScaleType/CENTER_CROP)
              (.setTypeface text-bottom android.graphics.Typeface/DEFAULT_BOLD)
              (set-text-size text-top default-text-size)
              (set-text-size text-bottom default-text-size)
              (set-text-size text-count default-text-size)
              (.setLayoutParams tile-view layout-params)
              (let [pad (make-dip context 4)
                    radius (make-dip context 10)
                    black android.graphics.Color/BLACK]
                (doseq [text-view [text-top text-bottom text-count]]
                  (.setPadding text-view pad pad pad pad)
                  (.setShadowLayer text-view radius 0 0 black))))
            (when (get item :add-emphasis?)
              (.setTypeface text-top android.graphics.Typeface/DEFAULT_BOLD)
              (.setGravity text-top android.view.Gravity/CENTER_HORIZONTAL))
            (when-not (get item :add-emphasis?)
              (.setTypeface text-top android.graphics.Typeface/DEFAULT)
              (.setGravity text-top android.view.Gravity/LEFT))
            (when-let [background (get item :background)]
              (.setBackgroundResource img background))
            (if (nil? (get item :tag))
              (load-pic img (get item :userhash) (get item :pichash))
              (future
                (let [tag (get-single-tag-data item)]
                  (on-ui
                    (load-pic img (get tag :userhash) (get tag :pichash))))))
            (.setText text-top (or (get item :title)
                                   (get item :body)
                                   (get item :tag)))
            (if-let [subtitle (get item :subtitle)]
              (.setText text-bottom subtitle)
              (.setText text-bottom nil))
            (if (and (get item :count) (> (get item :count) 0))
              (.setText text-count (str (get item :count)))
              (.setText text-count nil))
            tile-view))))
    (.setOnItemClickListener
      view
      (proxy [android.widget.AdapterView$OnItemClickListener] []
        (onItemClick [parent v position id]
          (tile-action context (get content position)))))))

(defn get-grid-view
  ([context content] (get-grid-view context content false))
  ([context content make-height-fit-content?]
   (let [tile-view-min (make-dip context default-tile-width)
         view (proxy [android.widget.GridView] [context]
                (onMeasure [width-spec height-spec]
                  (let [w (android.view.View$MeasureSpec/getSize width-spec)
                        num-columns (int (/ w tile-view-min))]
                    (.setNumColumns this num-columns))
                  (if make-height-fit-content?
                    (let [params (.getLayoutParams this)
                          size (bit-shift-right java.lang.Integer/MAX_VALUE 2)
                          mode android.view.View$MeasureSpec/AT_MOST
                          h-spec (android.view.View$MeasureSpec/makeMeasureSpec
                                                    size mode)]
                      (proxy-super onMeasure width-spec h-spec))
                    (proxy-super onMeasure width-spec height-spec))))]
     (when (> (count content) 0)
       (set-grid-view-tiles context content view))
     view)))

(defn get-post-view
  [context content]
  (let [view (make-ui context [:scroll-view {}
                               [:linear-layout {:orientation 1}
                                [:text-view {:layout-width :fill
                                             :text-is-selectable true}]
                                [:text-view {:layout-width :fill}]]])
        linear-layout (.getChildAt view 0)
        text-view (.getChildAt linear-layout 0)
        date-view (.getChildAt linear-layout 1)
        grid-view (get-grid-view context [] true)
        pad (make-dip context 10)]
    (.setPadding text-view pad pad pad pad)
    (.setPadding date-view pad pad pad pad)
    (set-text-size text-view default-text-size)
    (set-text-size date-view default-text-size)
    (.addView linear-layout grid-view)
    (show-spinner
      context
      (get-string :loading)
      (fn []
        (let [; read values from database
              post (get-single-post-data content)
              user (get-single-user-data content)
              user-pointer (when (and (get post :ptrhash)
                                      (nil? (get post :ptrtime)))
                             (get-single-user-data
                               {:userhash (get post :ptrhash)}))
              post-pointer (when (get post :ptrtime) 
                             (get-single-post-data
                               {:userhash (get post :ptrhash)
                                :time (get post :ptrtime)}))
              pics (get-pic-data content (get content :time) true)
              fav (when-not (is-me? (get content :userhash))
                    (get-single-fav-data content))
              ; create tiles based on the values
              user-tile (assoc user
                               :background (get-resource :drawable :profile)
                               :add-emphasis? true
                               :title (get-string :author)
                               :subtitle (get user :title))
              user-pointer-tile (when user-pointer
                                  (assoc user-pointer
                                         :background
                                         (get-resource :drawable :profile)
                                         :add-emphasis? true
                                         :title (get-string :mentioned)
                                         :subtitle (get user-pointer :title)))
              post-pointer-tile (when post-pointer
                                  (assoc post-pointer
                                         :background
                                         (get-resource :drawable :post)
                                         :add-emphasis? true
                                         :title (get-string :in_reply_to)))
              action-tile (if (is-me? (get content :userhash))
                            {:title (get-string :edit)
                             :add-emphasis? true
                             :background (get-resource :drawable :edit_post)
                             :type :custom-func
                             :func (fn [context item]
                                     (show-edit-post-dialog context post pics))}
                            {:title (if (= 1 (get fav :status))
                                      (get-string :remove_from_favorites)
                                      (get-string :add_to_favorites))
                             :add-emphasis? true
                             :background (if (= 1 (get fav :status))
                                           (get-resource :drawable :remove_fav)
                                           (get-resource :drawable :add_fav))
                             :type :toggle-fav
                             :userhash (get content :userhash)
                             :ptrtime (get content :time)
                             :status (get fav :status)
                             :time (get fav :time)})
              ; combine the tiles together
              total-results (-> [user-tile
                                 user-pointer-tile
                                 post-pointer-tile
                                 action-tile]
                                (concat pics)
                                (remove-dupes-and-nils)
                                (vec))
              body-text (tags-encode :post (get post :body))]
          (if (nil? (get post :body))
            (on-ui (toast (get-string :lost_post)))
            (on-ui (set-text-content context text-view body-text)
                   (let [date-format (java.text.DateFormat/getDateTimeInstance
                                       java.text.DateFormat/MEDIUM
                                       java.text.DateFormat/SHORT)]
                     (.setText date-view (->> (get post :time)
                                              (java.util.Date.)
                                              (.format date-format))))
                   (set-grid-view-tiles context total-results grid-view))))
        false))
    view))

(defn get-gallery-view
  [context content]
  (let [view (make-ui context [:view-pager {}])]
    (show-spinner
      context
      (get-string :loading)
      (fn []
        (let [pics (get-pic-data content (get content :ptrtime) false)]
          (on-ui
            (.setAdapter
              view
              (proxy [android.support.v4.view.PagerAdapter] []
                (destroyItem [container position object]
                  (.removeView container object))
                (getCount [] (count pics))
                (instantiateItem [container pos]
                  (let [image-view (android.widget.ImageView. context)
                        bitmap (-> (get-pic-path (get-in pics [pos :userhash])
                                                 (get-in pics [pos :pichash]))
                                   (path-to-bitmap full-size))]
                    (.setImageBitmap image-view bitmap)
                    (.addView container image-view)
                    image-view))
                (isViewFromObject [view object] (= view object))
                (setPrimaryItem [container position object])))
            (.setCurrentItem view
                             (->> pics
                                  (filter (fn [pic]
                                            (java.util.Arrays/equals
                                              (get pic :pichash)
                                              (get content :pichash))))
                                  (first)
                                  (.indexOf pics)))))
        false))
    view))

(defn get-user-view
  [context content]
  (let [grid-view (get-grid-view context [])]
    (show-spinner
      context
      (get-string :loading)
      (fn []
        (let [user (get-single-user-data content)
              fav (when-not (is-me? (get user :userhash))
                    (get-single-fav-data {:userhash (get user :userhash)}))
              first-tiles (when (nil? (get content :page))
                            [{:title (get-string :profile)
                              :add-emphasis? true
                              :background (get-resource :drawable :profile)
                              :userhash (get user :userhash)
                              :pichash (get user :pichash)
                              :type :custom-func
                              :func (fn [context item]
                                      (show-profile-dialog context user))}
                             {:title (get-string :favorites)
                              :add-emphasis? true
                              :userhash (get user :userhash)
                              :background (get-resource :drawable :favs)
                              :type :fav}
                             (when-not (is-me? (get user :userhash))
                               {:title (if (= 1 (get fav :status))
                                         (get-string :remove_from_favorites)
                                         (get-string :add_to_favorites))
                                :add-emphasis? true
                                :background
                                (if (= 1 (get fav :status))
                                  (get-resource :drawable :remove_fav)
                                  (get-resource :drawable :add_fav))
                                :type :custom-func
                                :func
                                (fn [context item]
                                  (if (= 1 (get fav :status))
                                    (show-remove-user-dialog context item)
                                    (toggle-fav context item false)))
                                :userhash (get user :userhash)
                                :status (get fav :status)
                                :time (get fav :time)})])
              posts (->> (for [tile (get-post-data content)]
                           (assoc tile
                                  :background
                                  (get-resource :drawable :post)))
                         (into [])
                         (add-last-tile content))
              grid-content (-> first-tiles
                               (concat posts)
                               (remove-dupes-and-nils)
                               (vec))]
          (on-ui (set-grid-view-tiles context grid-content grid-view)))
        false))
    grid-view))

(defn get-category-view
  [context content]
  (let [grid-view (get-grid-view context [])]
    (show-spinner
      context
      (get-string :loading)
      (fn []
        (let [first-tiles [(when (and (nil? (get content :subtype))
                                      (nil? (get content :tag))
                                      (nil? (get content :page)))
                             {:type :tag
                              :subtype (get content :type)
                              :title (get-string :tags)
                              :add-emphasis? true
                              :background (get-resource :drawable :tags)})]
              results (->> (for [tile (get-category-data content)]
                             (case (get tile :type)
                               :user (assoc tile
                                            :background
                                            (get-resource :drawable :profile))
                               :post (assoc tile
                                            :background
                                            (get-resource :drawable :post))
                               tile))
                           (into [])
                           (add-last-tile content))
              grid-content (-> first-tiles
                               (concat results)
                               (remove-dupes-and-nils)
                               (vec))]
          (on-ui (set-grid-view-tiles context grid-content grid-view)))
        false))
    grid-view))

(defn create-tab
  [action-bar title create-view]
  (try
    (let [tab (.newTab action-bar)
          fragment (proxy [android.app.Fragment] []
                     (onCreateView [layout-inflater viewgroup bundle]
                       (create-view)))
          listener (proxy [android.app.ActionBar$TabListener] []
                     (onTabSelected [tab ft]
                       (.add ft (get-resource :id :android/content) fragment))
                     (onTabUnselected [tab ft]
                       (.remove ft fragment))
                     (onTabReselected [tab ft]
                       (.detach ft fragment)
                       (.attach ft fragment)))]
      (.setText tab title)
      (.setTabListener tab listener)
      (.addTab action-bar tab))
    (catch java.lang.Exception e nil)))
